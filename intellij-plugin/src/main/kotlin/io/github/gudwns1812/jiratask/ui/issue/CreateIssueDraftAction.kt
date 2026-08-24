package io.github.gudwns1812.jiratask.ui.issue

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import io.github.gudwns1812.jiratask.application.CreateIssueCommand
import io.github.gudwns1812.jiratask.application.CreateIssueResult
import io.github.gudwns1812.jiratask.application.CreateIssueUseCase
import io.github.gudwns1812.jiratask.domain.jira.CreateRequestDraft
import io.github.gudwns1812.jiratask.domain.jira.JiraDraftReader
import io.github.gudwns1812.jiratask.infrastructure.GitRepository
import io.github.gudwns1812.jiratask.infrastructure.JiraClient
import io.github.gudwns1812.jiratask.ui.BranchSettings
import io.github.gudwns1812.jiratask.ui.IssueDraftSettings
import io.github.gudwns1812.jiratask.ui.JiraTaskAvailability
import io.github.gudwns1812.jiratask.ui.JiraTaskCommand
import io.github.gudwns1812.jiratask.ui.JiraTaskNotifications
import io.github.gudwns1812.jiratask.ui.JiraTaskSettings
import io.github.gudwns1812.jiratask.ui.SettingsLookup
import io.github.gudwns1812.jiratask.ui.applyAvailability
import io.github.gudwns1812.jiratask.ui.notifyAndRefresh
import io.github.gudwns1812.jiratask.ui.setup.SetupWizardLauncher
import java.io.File

/**
 * CLI의 `template` + `validate`에 대응하고, 그 위에 **실제 이슈 생성**까지 한다
 * (CLI의 `create`는 초안만 만들고 실제 생성을 거부하지만, 플러그인에서 초안만 만드는 것은 의미가 없다).
 *
 * 다이얼로그를 열기 전에 채울 값을 백그라운드에서 모으고(브랜치 → 스토리 → 에픽·스프린트·기능 ID),
 * 확인을 누르면 다시 백그라운드에서 Jira에 만든다.
 */
class CreateIssueDraftAction @JvmOverloads constructor(
    private val settings: JiraTaskSettings = JiraTaskSettings(),
    private val availability: JiraTaskAvailability = JiraTaskAvailability(),
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
                ApplicationManager.getApplication().invokeLater { open(project, context) }
            }
        })
    }

    private fun open(project: Project?, context: IssueDraftContext) {
        CreateIssueDraftDialog(
            project,
            context,
            onRemember = { remembered -> project?.let { IssueDraftMemory.getInstance(it).remember(remembered) } },
            onCreate = { _, view -> view.draft?.let { create(project, context, it) } },
        ).show()
    }

    private fun create(project: Project?, context: IssueDraftContext, draft: CreateRequestDraft) {
        val branch = settings.forBranch()
        if (branch !is SettingsLookup.Ready) {
            JiraTaskNotifications.error(
                project,
                "Jira 연결 정보가 없어 이슈를 만들 수 없습니다: ${(branch as SettingsLookup.Missing).message}",
                NotificationAction.createSimple("설정 시작") { SetupWizardLauncher.launch(project) },
            )
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Jira 이슈 만들기", false) {
            override fun run(indicator: ProgressIndicator) {
                val result = CreateIssueUseCase(clientOf(branch.value)).execute(
                    CreateIssueCommand(
                        draft = draft,
                        issueTypeIds = settings.issueTypeIds(),
                        sprintId = context.sprintId,
                    ),
                )
                report(project, branch.value.baseUrl, result)
            }
        })
    }

    private fun report(project: Project?, baseUrl: String, result: CreateIssueResult) = when (result) {
        is CreateIssueResult.Created -> {
            val notes = buildList {
                if (result.unsetFields.isNotEmpty()) {
                    add("Jira에서 직접 채워야 하는 값: ${result.unsetFields.joinToString(", ")}")
                }
                if (result.linkFailed) {
                    add("스토리와 연결하지 못했습니다")
                }
            }
            notifyAndRefresh(
                project,
                if (notes.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING,
                (listOf("이슈를 만들었습니다: $baseUrl/browse/${result.key}") + notes).joinToString(" — "),
            )
        }
        is CreateIssueResult.Failure -> JiraTaskNotifications.error(project, "이슈 생성 실패: ${result.message}")
    }

    private fun clientOf(branch: BranchSettings): JiraClient =
        JiraClient(branch.baseUrl, branch.email, branch.apiToken)

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
                { clientOf(branch.value) }
            }
            is SettingsLookup.Missing -> null
        }
}
