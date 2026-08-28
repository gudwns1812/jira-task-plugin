package io.github.gudwns1812.jiratask.ui.commit

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import io.github.gudwns1812.jiratask.ui.JiraTaskSettings
import io.github.gudwns1812.jiratask.ui.task.SprintTasks
import io.github.gudwns1812.jiratask.ui.task.TaskAnswer

/**
 * 커밋 창의 두 가지 일을 맡는다 — 열릴 때 이슈 키를 보정하고, 커밋 직전에 RULE.md 5.2를 확인한다.
 *
 * 창이 열리는 순간의 기본 메시지는 [JiraTaskCommitMessageProvider]가 만든다. 여기서 한 번 더 채우는 것은
 * 그 뒤에 작업 번호가 바뀐 경우를 위한 안전망이다.
 *
 * **어느 쪽도 커밋을 막지 않는다.** 오프라인이거나 Jira가 죽었을 때 일을 못 하게 되면 안 된다.
 */
class JiraTaskCheckinHandlerFactory(
    private val issueKey: CommitIssueKey = CommitIssueKey(),
    /** 커밋이 끝난 뒤 할 일. 지금은 아무것도 하지 않는다 — 완료 전환은 automation이 맡는다(RULE.md 4.2). */
    private val onCheckedIn: (Project) -> Unit = {},
    /** 이슈 키 하나를 확인하는 방법. EDT에서 불리므로 캐시에 있는 답만 본다. */
    private val lookup: (Project, String) -> TaskAnswer = { project, key ->
        SprintTasks.getInstance(project).findCached(key)
    },
    /** 커밋 창이 열릴 때 캐시를 데워 둔다. 그래야 커밋 직전에 물어볼 것이 남아 있다. */
    private val warmUp: (Project) -> Unit = ::warmSprintTasks,
    /** 경고를 보여 주고 "그래도 커밋"인지 묻는다. */
    private val confirm: (Project, CommitGuardVerdict.Warn) -> Boolean = ::askAnyway,
) : CheckinHandlerFactory() {

    constructor(settings: JiraTaskSettings) : this(CommitIssueKey(settings))

    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler {
        issueKey.fill(PanelCommitMessage(panel), panel.project)
        warmUp(panel.project)
        return handlerFor(panel.project) { panel.commitMessage }
    }

    /**
     * 커밋 직전에 확인하고, 끝나면 지켜보는 훅.
     *
     * `beforeCheckin()`은 EDT에서 불린다. Jira 조회는 네트워크를 타므로 여기서 바로 부르면 커밋 창이
     * 멈춘다. 그래서 창이 열릴 때 백그라운드로 캐시를 데워 두고, 여기서는 캐시가 아는 답만 본다.
     * 아직 답이 없으면 조용히 넘어간다 — 커밋을 지연시키면서까지 확인할 만한 일이 아니다.
     */
    fun handlerFor(project: Project, message: () -> String?): CheckinHandler = object : CheckinHandler() {

        override fun beforeCheckin(): ReturnResult {
            val verdict = CommitGuard.inspect(message()) { key -> lookup(project, key) }

            return when (verdict) {
                is CommitGuardVerdict.Ok -> ReturnResult.COMMIT
                is CommitGuardVerdict.Warn ->
                    if (confirm(project, verdict)) ReturnResult.COMMIT else ReturnResult.CANCEL
            }
        }

        override fun checkinSuccessful() = onCheckedIn(project)
    }

    /** 현재 프로젝트 설정과 작업 번호로 만든 이슈 키. 둘 중 하나라도 없으면 null. */
    fun issueKeyFor(project: Project): String? = issueKey.issueKeyFor(project)
}

/** 커밋 창이 열리는 동안 스프린트 목록을 백그라운드로 읽어 둔다. */
private fun warmSprintTasks(project: Project) {
    ApplicationManager.getApplication().executeOnPooledThread {
        if (!project.isDisposed) {
            SprintTasks.getInstance(project).current()
        }
    }
}

/**
 * 경고를 보여 주고 그대로 커밋할지 묻는다. 기본 버튼은 "그래도 커밋"이다 —
 * 가드는 규칙을 일깨우는 장치이지 통과 의례가 아니다.
 */
private fun askAnyway(project: Project, warn: CommitGuardVerdict.Warn): Boolean {
    if (ApplicationManager.getApplication().isUnitTestMode) {
        return true
    }
    return Messages.showYesNoDialog(
        project,
        warn.message,
        "Jira 작업 확인",
        "그래도 커밋",
        "취소",
        Messages.getWarningIcon(),
    ) == Messages.YES
}

/** [CheckinProjectPanel]을 [CommitMessageTarget]으로 감싼다. */
private class PanelCommitMessage(private val panel: CheckinProjectPanel) : CommitMessageTarget {

    override val currentMessage: String?
        get() = panel.commitMessage

    override fun setMessage(text: String) = panel.setCommitMessage(text)
}
