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

interface CommitMessageTarget {
    val currentMessage: String?
    fun setMessage(text: String)
}

sealed interface IssueKeyLookup {

    data class Found(val issueKey: String) : IssueKeyLookup

    sealed interface Missing : IssueKeyLookup {
        val message: String
    }

    data class NoTaskNumber(override val message: String = NO_TASK_NUMBER) : Missing

    data class Unavailable(override val message: String) : Missing

    companion object {
        const val NO_TASK_NUMBER: String =
            "현재 Jira 작업 번호가 없습니다. Start Jira Task로 저장하면 커밋 메시지에 이슈 키가 붙습니다."
    }
}

class CommitIssueKey(
    private val settings: JiraTaskSettings = JiraTaskSettings(),
    private val refreshTaskNumber: (Project) -> Unit = { CurrentTaskState.getInstance(it).refresh() },
    private val background: (() -> Unit) -> Unit = {
        ApplicationManager.getApplication().executeOnPooledThread(Runnable(it))
    },
    private val onEdt: (() -> Unit) -> Unit = {
        ApplicationManager.getApplication().invokeLater(Runnable(it))
    },
    private val announce: (Project, IssueKeyLookup.Missing) -> Unit = MissingIssueKeyNotice::show,
    private val announced: ConcurrentMap<String, String> = SHARED_ANNOUNCEMENTS,
) {
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

    fun fill(target: CommitMessageTarget, project: Project) {
        when (val first = lookup(project)) {
            is IssueKeyLookup.Found -> apply(target, first.issueKey, project)
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

    fun issueKeyFor(project: Project): String? = (lookup(project) as? IssueKeyLookup.Found)?.issueKey

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

    private fun forget(project: Project) {
        announced.remove(project.locationHash)
    }

    companion object {
        private val SHARED_ANNOUNCEMENTS: ConcurrentMap<String, String> = ConcurrentHashMap()
    }
}

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
