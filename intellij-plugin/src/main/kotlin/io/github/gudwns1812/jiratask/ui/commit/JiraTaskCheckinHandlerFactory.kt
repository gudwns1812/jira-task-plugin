package io.github.gudwns1812.jiratask.ui.commit

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import io.github.gudwns1812.jiratask.domain.task.TaskNumberError
import io.github.gudwns1812.jiratask.domain.task.TaskNumbers
import io.github.gudwns1812.jiratask.ui.JiraTaskSettings
import io.github.gudwns1812.jiratask.ui.SettingsLookup
import io.github.gudwns1812.jiratask.ui.task.CurrentTaskState

/**
 * Git 커밋 다이얼로그가 열릴 때 저장된 작업 번호로 이슈 키를 붙여준다.
 * 작업 번호나 프로젝트 키가 없으면 아무 것도 하지 않는다 — 커밋을 막지는 않는다.
 *
 * 값은 [CurrentTaskState]의 캐시에서 읽는다. 커밋 다이얼로그 생성은 EDT에서 일어나므로
 * 여기서 git을 직접 호출하면 UI가 멈춘다.
 */
class JiraTaskCheckinHandlerFactory(
    private val settings: JiraTaskSettings = JiraTaskSettings(),
) : CheckinHandlerFactory() {

    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler {
        issueKeyFor(panel.project)?.let { issueKey ->
            if (!JiraCommitMessages.startsWith(panel.commitMessage, issueKey)) {
                panel.setCommitMessage(JiraCommitMessages.withIssueKey(panel.commitMessage, issueKey))
            }
        }
        return CheckinHandler.DUMMY
    }

    /** 현재 프로젝트 설정과 작업 번호로 만든 이슈 키. 둘 중 하나라도 없으면 null. */
    fun issueKeyFor(project: Project): String? {
        val taskNumber = CurrentTaskState.getInstance(project).taskNumber ?: return null
        val lookup = settings.forCommit()
        if (lookup !is SettingsLookup.Ready) {
            return null
        }

        return try {
            TaskNumbers.createTaskKey(lookup.value.projectKey, taskNumber)
        } catch (error: TaskNumberError) {
            null
        }
    }
}
