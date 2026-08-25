package io.github.gudwns1812.jiratask.ui.commit

import com.intellij.notification.NotificationAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import io.github.gudwns1812.jiratask.domain.task.TaskNumberError
import io.github.gudwns1812.jiratask.domain.task.TaskNumbers
import io.github.gudwns1812.jiratask.ui.JiraTaskNotifications
import io.github.gudwns1812.jiratask.ui.JiraTaskSettings
import io.github.gudwns1812.jiratask.ui.SettingsLookup
import io.github.gudwns1812.jiratask.ui.setup.SetupWizardLauncher
import io.github.gudwns1812.jiratask.ui.task.CurrentTaskState
import io.github.gudwns1812.jiratask.ui.task.StartTaskAction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/** 커밋 메시지를 읽고 쓰는 최소 창구. 테스트가 커밋 창 전체를 흉내내지 않도록 갈라 뒀다. */
interface CommitMessageTarget {
    val currentMessage: String?
    fun setMessage(text: String)
}

/** 커밋 메시지에 붙일 이슈 키를 찾은 결과. */
sealed interface IssueKeyLookup {

    data class Found(val issueKey: String) : IssueKeyLookup

    /** 이슈 키를 만들 수 없는 이유. 사용자에게 그대로 보여줄 문구를 담는다. */
    sealed interface Missing : IssueKeyLookup {
        val message: String
    }

    /** 작업 번호만 없다. 캐시가 아직 비었을 수 있으니 git을 읽어 다시 시도할 값이다. */
    data class NoTaskNumber(override val message: String = NO_TASK_NUMBER) : Missing

    /** 설정이 없거나 값이 잘못됐다. git을 다시 읽어도 달라지지 않는다. */
    data class Unavailable(override val message: String) : Missing

    companion object {
        const val NO_TASK_NUMBER: String =
            "현재 Jira 작업 번호가 없습니다. Start Jira Task로 저장하면 커밋 메시지에 이슈 키가 붙습니다."
    }
}

/**
 * 커밋 메시지에 붙일 이슈 키를 찾고, 못 찾으면 이유를 알린다. 커밋 창으로 가는 두 경로가 함께 쓴다 —
 * 창이 열릴 때 기본 메시지를 만드는 [JiraTaskCommitMessageProvider]와,
 * 커밋 직전에 메시지를 보정하는 [JiraTaskCheckinHandlerFactory].
 *
 * 값의 정본은 로컬 git 설정(`jira-task.current-task`)이지만 두 경로 모두 EDT에서 불리므로
 * 여기서 git을 직접 부르면 UI가 멈춘다. 그래서 [CurrentTaskState] 캐시를 먼저 보고,
 * 캐시가 비었으면 백그라운드로 git을 읽는다. 캐시가 비는 경우는 흔하다 — 상태 표시줄 위젯을
 * 숨겨 뒀거나, CLI로 작업 번호를 바꿨거나, 브랜치를 갈아탄 직후다.
 */
class CommitIssueKey(
    private val settings: JiraTaskSettings = JiraTaskSettings(),
    /** 캐시가 비었을 때 git에서 작업 번호를 다시 읽는 방법. 백그라운드에서만 호출한다. */
    private val refreshTaskNumber: (Project) -> Unit = { CurrentTaskState.getInstance(it).refresh() },
    private val background: (() -> Unit) -> Unit = {
        ApplicationManager.getApplication().executeOnPooledThread(Runnable(it))
    },
    private val onEdt: (() -> Unit) -> Unit = {
        ApplicationManager.getApplication().invokeLater(Runnable(it))
    },
    private val announce: (Project, IssueKeyLookup.Missing) -> Unit = MissingIssueKeyNotice::show,
    /**
     * 프로젝트별로 마지막에 알린 이유. 커밋 창 경로가 둘이라 인스턴스 바깥에서 공유하고,
     * 테스트는 자기 것을 넘겨 서로 간섭하지 않게 한다.
     */
    private val announced: ConcurrentMap<String, String> = SHARED_ANNOUNCEMENTS,
) {

    /**
     * 커밋 창이 열릴 때 쓸 메시지. 붙일 이슈 키가 없으면 null을 주고 플랫폼 기본값에 맡긴다.
     * 캐시가 비었으면 백그라운드로 git을 읽어 두어 다음 번에는 값이 있게 한다.
     */
    fun messageFor(project: Project, current: String?): String? = when (val lookup = lookup(project)) {
        is IssueKeyLookup.Found -> {
            forget(project)
            JiraCommitMessages.withIssueKey(current, lookup.issueKey)
        }
        is IssueKeyLookup.Unavailable -> {
            announceOnce(project, lookup)
            null
        }
        is IssueKeyLookup.NoTaskNumber -> {
            refreshAndAnnounceIfStillMissing(project)
            null
        }
    }

    /** 이미 열려 있는 커밋 창의 메시지를 보정한다. 캐시가 비었으면 git을 읽고 나서 채운다. */
    fun fill(target: CommitMessageTarget, project: Project) {
        when (val first = lookup(project)) {
            is IssueKeyLookup.Found -> apply(target, first.issueKey, project)
            // 설정 문제는 git을 다시 읽어도 그대로다. 바로 알린다.
            is IssueKeyLookup.Unavailable -> announceOnce(project, first)
            is IssueKeyLookup.NoTaskNumber -> background {
                refreshTaskNumber(project)
                val retried = lookup(project)
                onEdt {
                    if (!project.isDisposed) {
                        when (retried) {
                            is IssueKeyLookup.Found -> apply(target, retried.issueKey, project)
                            is IssueKeyLookup.Missing -> announceOnce(project, retried)
                        }
                    }
                }
            }
        }
    }

    /** 현재 프로젝트 설정과 작업 번호로 만든 이슈 키. 둘 중 하나라도 없으면 null. */
    fun issueKeyFor(project: Project): String? = (lookup(project) as? IssueKeyLookup.Found)?.issueKey

    /** 이슈 키를 만들 수 있는지, 못 만들면 왜 못 만드는지. */
    fun lookup(project: Project): IssueKeyLookup {
        val settingsLookup = settings.forCommit()
        if (settingsLookup is SettingsLookup.Missing) {
            return IssueKeyLookup.Unavailable(settingsLookup.message)
        }

        val projectKey = (settingsLookup as SettingsLookup.Ready).value.projectKey
        val taskNumber = CurrentTaskState.getInstance(project).taskNumber
            ?: return IssueKeyLookup.NoTaskNumber()

        return try {
            IssueKeyLookup.Found(TaskNumbers.createTaskKey(projectKey, taskNumber))
        } catch (error: TaskNumberError) {
            IssueKeyLookup.Unavailable(error.message ?: IssueKeyLookup.NO_TASK_NUMBER)
        }
    }

    /**
     * 캐시가 비어 있으니 git을 읽어 둔다. 이미 만들어진 메시지를 뒤늦게 바꾸지는 않고,
     * 그래도 없을 때만 이유를 알린다 — 값이 있는데 경고를 띄우면 거짓말이 된다.
     */
    private fun refreshAndAnnounceIfStillMissing(project: Project) = background {
        refreshTaskNumber(project)
        val retried = lookup(project)
        if (retried is IssueKeyLookup.Missing) {
            onEdt {
                if (!project.isDisposed) {
                    announceOnce(project, retried)
                }
            }
        }
    }

    /** 사용자가 이미 쓰고 있는 메시지를 흔들지 않도록, 같은 키로 시작하면 그대로 둔다. */
    private fun apply(target: CommitMessageTarget, issueKey: String, project: Project) {
        forget(project)

        if (!JiraCommitMessages.startsWith(target.currentMessage, issueKey)) {
            target.setMessage(JiraCommitMessages.withIssueKey(target.currentMessage, issueKey))
        }
    }

    private fun announceOnce(project: Project, missing: IssueKeyLookup.Missing) {
        if (announced.put(project.locationHash, missing.message) != missing.message) {
            announce(project, missing)
        }
    }

    /** 문제가 풀렸으니 다음에 같은 이유가 생기면 다시 알릴 수 있게 한다. */
    private fun forget(project: Project) {
        announced.remove(project.locationHash)
    }

    companion object {
        private val SHARED_ANNOUNCEMENTS: ConcurrentMap<String, String> = ConcurrentHashMap()
    }
}

/** 이슈 키를 붙이지 못한 이유와 다음에 할 일을 알림 하나로 안내한다. */
object MissingIssueKeyNotice {

    fun show(project: Project, missing: IssueKeyLookup.Missing) {
        JiraTaskNotifications.warning(project, missing.message, actionFor(project, missing))
    }

    private fun actionFor(project: Project, missing: IssueKeyLookup.Missing): NotificationAction = when (missing) {
        is IssueKeyLookup.NoTaskNumber ->
            NotificationAction.createSimple("작업 번호 저장") { StartTaskAction.open(project) }
        is IssueKeyLookup.Unavailable ->
            NotificationAction.createSimple("설정 시작") { SetupWizardLauncher.launch(project) }
    }
}
