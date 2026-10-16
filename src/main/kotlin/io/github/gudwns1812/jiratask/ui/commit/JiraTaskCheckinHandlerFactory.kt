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

class JiraTaskCheckinHandlerFactory(
    private val issueKey: CommitIssueKey = CommitIssueKey(),
    private val onCheckedIn: (Project) -> Unit = {},
    private val lookup: (Project, String) -> TaskAnswer = { project, key ->
        SprintTasks.getInstance(project).findCached(key)
    },
    private val warmUp: (Project) -> Unit = ::warmSprintTasks,
    private val confirm: (Project, CommitGuardVerdict.Warn) -> Boolean = ::askAnyway,
) : CheckinHandlerFactory() {

    constructor(settings: JiraTaskSettings) : this(CommitIssueKey(settings))

    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler {
        issueKey.fill(PanelCommitMessage(panel), panel.project)
        warmUp(panel.project)
        return handlerFor(panel.project) { panel.commitMessage }
    }

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

    fun issueKeyFor(project: Project): String? = issueKey.issueKeyFor(project)
}

private fun warmSprintTasks(project: Project) {
    ApplicationManager.getApplication().executeOnPooledThread {
        if (!project.isDisposed) {
            SprintTasks.getInstance(project).current()
        }
    }
}

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

private class PanelCommitMessage(private val panel: CheckinProjectPanel) : CommitMessageTarget {

    override val currentMessage: String?
        get() = panel.commitMessage

    override fun setMessage(text: String) = panel.setCommitMessage(text)
}
