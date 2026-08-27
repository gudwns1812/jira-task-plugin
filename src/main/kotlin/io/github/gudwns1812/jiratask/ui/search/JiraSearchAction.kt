package io.github.gudwns1812.jiratask.ui.search

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import io.github.gudwns1812.jiratask.domain.jira.JiraIssueEditor
import io.github.gudwns1812.jiratask.domain.jira.SprintTaskReader
import io.github.gudwns1812.jiratask.infrastructure.JiraClient
import io.github.gudwns1812.jiratask.ui.JiraTaskAvailability
import io.github.gudwns1812.jiratask.ui.JiraTaskCommand
import io.github.gudwns1812.jiratask.ui.JiraTaskNotifications
import io.github.gudwns1812.jiratask.ui.JiraTaskSettings
import io.github.gudwns1812.jiratask.ui.SettingsLookup
import io.github.gudwns1812.jiratask.ui.TaskTransitionSettings
import io.github.gudwns1812.jiratask.ui.applyAvailability
import io.github.gudwns1812.jiratask.ui.task.SprintTasks

/** Jira를 여는 대신 IDE 안에서 이슈를 찾아보고 제목·설명을 고친다. */
class JiraSearchAction @JvmOverloads constructor(
    private val settings: JiraTaskSettings = JiraTaskSettings(),
    private val availability: JiraTaskAvailability = JiraTaskAvailability(),
) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.applyAvailability(availability.of(JiraTaskCommand.SEARCH, event.project?.basePath != null))
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val ready = settings.forTaskTransition()
        if (ready is SettingsLookup.Missing) {
            JiraTaskNotifications.warning(project, ready.message)
            return
        }

        open(project, (ready as SettingsLookup.Ready).value)
    }

    private fun open(project: Project, jira: TaskTransitionSettings) {
        val dialog = JiraSearchDialog(project, jira.projectKey)
        val client = { JiraClient(jira.baseUrl, jira.email, jira.apiToken) }

        dialog.onSearch = { query -> runSearch(project, dialog, jira, client(), query) }
        dialog.onOpen = { key -> runOpen(project, dialog, client(), key) }
        dialog.onSave = { edit -> runSave(project, dialog, client(), edit) }

        dialog.show()
    }

    private fun runSearch(
        project: Project,
        dialog: JiraSearchDialog,
        jira: TaskTransitionSettings,
        reader: SprintTaskReader,
        query: JiraSearchQuery,
    ) = inBackground(project) {
        val result = runCatching {
            when (query) {
                is JiraSearchQuery.Empty -> emptyList()
                is JiraSearchQuery.ByKey -> listOfNotNull(reader.task(query.issueKey))
                is JiraSearchQuery.ByText -> reader.searchByText(jira.projectKey, query.text, SEARCH_LIMIT)
            }
        }
        onEdt(project) {
            result
                .onSuccess(dialog::showResults)
                .onFailure { dialog.showProblem(it.message ?: "Jira 검색에 실패했습니다.") }
        }
    }

    private fun runOpen(project: Project, dialog: JiraSearchDialog, editor: JiraIssueEditor, key: String) =
        inBackground(project) {
            val result = runCatching { editor.detail(key) }
            onEdt(project) {
                result
                    .onSuccess { detail ->
                        if (detail == null) dialog.showProblem("$key 이슈를 열지 못했습니다.") else dialog.showDetail(detail)
                    }
                    .onFailure { dialog.showProblem(it.message ?: "Jira 이슈를 읽지 못했습니다.") }
            }
        }

    /**
     * 저장한다. 실패하면 화면을 되돌리지 않는다 — 사용자가 친 글을 잃게 만들면 안 된다.
     * 성공한 뒤에는 캐시를 비운다. 제목이 바뀌었으니 목록도 낡았다.
     */
    private fun runSave(project: Project, dialog: JiraSearchDialog, editor: JiraIssueEditor, edit: IssueEdit) =
        inBackground(project) {
            val result = runCatching {
                editor.updateSummaryAndDescription(edit.original.key, edit.summary.trim(), edit.description.trim())
            }
            onEdt(project) {
                result
                    .onSuccess {
                        dialog.markSaved()
                        SprintTasks.getInstance(project).invalidate()
                        JiraTaskNotifications.notify(
                            project,
                            "저장했습니다: ${edit.original.key}",
                            NotificationType.INFORMATION,
                        )
                    }
                    .onFailure { dialog.showProblem("저장하지 못했습니다: ${it.message.orEmpty()}") }
            }
        }

    private fun inBackground(project: Project, block: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!project.isDisposed) {
                block()
            }
        }
    }

    /**
     * 검색 창은 모달이다. 풀 스레드에서 예약한 `invokeLater`는 기본이 비모달이라 창이 닫힐 때까지
     * 실행되지 않으므로, 열려 있는 창을 갱신하려면 [ModalityState.any]로 올려야 한다.
     */
    private fun onEdt(project: Project, block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(
            { if (!project.isDisposed) block() },
            ModalityState.any(),
            project.disposed,
        )
    }

    companion object {
        const val SEARCH_LIMIT: Int = 25
    }
}
