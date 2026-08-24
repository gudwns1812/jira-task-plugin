package io.github.gudwns1812.jiratask.ui.issue

import com.intellij.notification.NotificationAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import io.github.gudwns1812.jiratask.domain.jira.JiraDraftReader
import io.github.gudwns1812.jiratask.infrastructure.GitRepository
import io.github.gudwns1812.jiratask.infrastructure.JiraClient
import io.github.gudwns1812.jiratask.ui.IssueDraftSettings
import io.github.gudwns1812.jiratask.ui.JiraTaskAvailability
import io.github.gudwns1812.jiratask.ui.JiraTaskCommand
import io.github.gudwns1812.jiratask.ui.JiraTaskNotifications
import io.github.gudwns1812.jiratask.ui.JiraTaskSettings
import io.github.gudwns1812.jiratask.ui.SettingsLookup
import io.github.gudwns1812.jiratask.ui.applyAvailability
import io.github.gudwns1812.jiratask.ui.setup.SetupWizardLauncher
import java.io.File

/**
 * CLI의 `template` + `validate` + `create --dry-run`에 대응하는 액션.
 *
 * 다이얼로그를 열기 전에 채울 값을 백그라운드에서 모은다(현재 브랜치 → 스토리 키 → Jira에서 기능 ID).
 * 폼에서 손으로 쓸 것을 줄이려는 것이므로, 이 준비가 실패해도 아는 값까지만 채워 그대로 연다.
 */
class CreateIssueDraftAction @JvmOverloads constructor(
    private val settings: JiraTaskSettings = JiraTaskSettings(),
    private val availability: JiraTaskAvailability = JiraTaskAvailability(),
    private val openDialog: (Project?, IssueDraftContext) -> Unit = { project, context ->
        CreateIssueDraftDialog(project, context) { remembered ->
            project?.let { IssueDraftMemory.getInstance(it).remember(remembered) }
        }.show()
    },
) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.applyAvailability(availability.of(JiraTaskCommand.ISSUE_DRAFT, event.project != null))
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project
        when (val lookup = settings.forIssueDraft()) {
            is SettingsLookup.Missing -> JiraTaskNotifications.error(
                project,
                lookup.message,
                NotificationAction.createSimple("설정 시작") { SetupWizardLauncher.launch(project) },
            )
            is SettingsLookup.Ready -> prepareAndOpen(project, lookup.value)
        }
    }

    private fun prepareAndOpen(project: Project?, draftSettings: IssueDraftSettings) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Jira 이슈 정보 읽기", false) {
            override fun run(indicator: ProgressIndicator) {
                val context = contextReader(project, draftSettings).read()
                ApplicationManager.getApplication().invokeLater { openDialog(project, context) }
            }
        })
    }

    private fun contextReader(project: Project?, draftSettings: IssueDraftSettings): IssueDraftContextReader =
        IssueDraftContextReader(
            settings = draftSettings,
            remembered = project?.let { IssueDraftMemory.getInstance(it).remembered() } ?: RememberedInput(),
            branch = project?.basePath?.let { GitRepository(File(it)).currentBranch() },
            createReader = draftReaderFactory(),
        )

    /** Jira 연결 정보가 있을 때만 조회한다. 토큰 조회는 이미 백그라운드다. */
    private fun draftReaderFactory(): (() -> JiraDraftReader)? =
        when (val branch = settings.forBranch()) {
            is SettingsLookup.Ready -> {
                { JiraClient(branch.value.baseUrl, branch.value.email, branch.value.apiToken) }
            }
            is SettingsLookup.Missing -> null
        }
}
