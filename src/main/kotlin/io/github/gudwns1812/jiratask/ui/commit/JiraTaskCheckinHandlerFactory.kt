package io.github.gudwns1812.jiratask.ui.commit

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import io.github.gudwns1812.jiratask.ui.JiraTaskSettings
import io.github.gudwns1812.jiratask.ui.task.AdvanceCurrentTask
import io.github.gudwns1812.jiratask.ui.task.CurrentTaskState
import io.github.gudwns1812.jiratask.ui.task.TaskTarget

/**
 * 커밋 워크플로가 시작될 때 커밋 메시지의 이슈 키를 한 번 더 보정한다.
 *
 * 창이 열리는 순간의 기본 메시지는 [JiraTaskCommitMessageProvider]가 만든다. 이 훅은 그 뒤에
 * 작업 번호가 바뀐 경우(다른 브랜치로 갈아탔거나 CLI로 번호를 바꾼 경우)를 위한 안전망이다.
 * 커밋을 막지는 않는다.
 */
class JiraTaskCheckinHandlerFactory(
    private val issueKey: CommitIssueKey = CommitIssueKey(),
    /** 커밋이 끝난 뒤 할 일. 기본은 작업 이슈를 완료로 옮기는 것이다. */
    private val onCheckedIn: (Project) -> Unit = ::advanceCurrentTaskToDone,
) : CheckinHandlerFactory() {

    constructor(settings: JiraTaskSettings) : this(CommitIssueKey(settings))

    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler {
        issueKey.fill(PanelCommitMessage(panel), panel.project)
        return handlerFor(panel.project)
    }

    /** 커밋 성공만 지켜보는 훅. 검사도 하지 않고 커밋을 막지도 않는다. */
    fun handlerFor(project: Project): CheckinHandler = object : CheckinHandler() {
        override fun checkinSuccessful() = onCheckedIn(project)
    }

    /** 현재 프로젝트 설정과 작업 번호로 만든 이슈 키. 둘 중 하나라도 없으면 null. */
    fun issueKeyFor(project: Project): String? = issueKey.issueKeyFor(project)
}

/**
 * 커밋이 성공했으니 작업 이슈를 완료로 옮긴다. 작업 번호는 [CurrentTaskState] 캐시에서 읽는다 —
 * `checkinSuccessful()`은 EDT에서 불리므로 여기서 git이나 Jira를 직접 부를 수 없다.
 */
private fun advanceCurrentTaskToDone(project: Project) {
    CurrentTaskState.getInstance(project).taskNumber?.let { taskNumber ->
        AdvanceCurrentTask.inBackground(project, taskNumber, TaskTarget.DONE)
    }
}

/** [CheckinProjectPanel]을 [CommitMessageTarget]으로 감싼다. */
private class PanelCommitMessage(private val panel: CheckinProjectPanel) : CommitMessageTarget {

    override val currentMessage: String?
        get() = panel.commitMessage

    override fun setMessage(text: String) = panel.setCommitMessage(text)
}
