package io.github.gudwns1812.jiratask.ui.branch

import com.intellij.notification.NotificationAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.openapi.ui.ValidationInfo
import io.github.gudwns1812.jiratask.application.CreateBranchCommand
import io.github.gudwns1812.jiratask.application.CreateBranchUseCase
import io.github.gudwns1812.jiratask.infrastructure.GitRepository
import io.github.gudwns1812.jiratask.infrastructure.JiraClient
import io.github.gudwns1812.jiratask.ui.BranchSettings
import io.github.gudwns1812.jiratask.ui.JiraTaskNotifications
import io.github.gudwns1812.jiratask.ui.JiraTaskSettings
import io.github.gudwns1812.jiratask.ui.SettingsLookup
import io.github.gudwns1812.jiratask.ui.setup.SetupWizardLauncher
import java.io.File
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/** 스토리 번호와 작업명을 받는 다이얼로그. 입력 중에도 도메인 규칙으로 안내한다. */
class CreateStoryBranchDialog(project: Project?) : DialogWrapper(project, false) {

    val storyNumberField: JBTextField = JBTextField()
    val nameField: JBTextField = JBTextField()

    init {
        title = "Create Story Branch"
        setOKButtonText("브랜치 만들기")
        init()
        listOf(storyNumberField, nameField).forEach { field ->
            field.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(event: DocumentEvent) {
                    isOKActionEnabled = values().problem() == null
                }
            })
        }
        isOKActionEnabled = false
    }

    override fun createCenterPanel(): JComponent = panel {
        row("스토리 번호:") { cell(storyNumberField).columns(COLUMNS_MEDIUM) }
            .rowComment("현재 프로젝트의 Jira 스토리 번호(0보다 큰 정수)입니다.")
        row("작업명:") { cell(nameField).columns(COLUMNS_MEDIUM) }
            .rowComment("소문자 영문·숫자·하이픈으로 된 짧은 이름입니다. 브랜치는 feat/<번호>-<작업명>으로 만듭니다.")
    }

    override fun getPreferredFocusedComponent(): JComponent = storyNumberField

    // 다이얼로그를 띄우지 않고도 안내 문구를 확인할 수 있게 공개 범위를 넓힌다.
    public override fun doValidate(): ValidationInfo? = values().problem()?.let { ValidationInfo(it) }

    fun values(): BranchFormInput = BranchFormInput(storyNumberField.text, nameField.text)
}

/** CLI의 `branch create`에 대응하는 액션. */
class CreateStoryBranchAction @JvmOverloads constructor(
    private val settings: JiraTaskSettings = JiraTaskSettings(),
) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project?.basePath != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project
        when (val lookup = settings.forBranch()) {
            is SettingsLookup.Missing -> JiraTaskNotifications.error(
                project,
                lookup.message,
                NotificationAction.createSimple("설정 시작") { SetupWizardLauncher.launch(project) },
            )
            is SettingsLookup.Ready -> {
                val dialog = CreateStoryBranchDialog(project)
                if (dialog.showAndGet()) {
                    createBranch(project, lookup.value, dialog.values().trimmed())
                }
            }
        }
    }

    private fun createBranch(project: Project?, branchSettings: BranchSettings, input: BranchFormInput) {
        val workingDirectory = project?.basePath?.let(::File) ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "스토리 브랜치 만들기", false) {
            override fun run(indicator: ProgressIndicator) {
                val result = CreateBranchUseCase(
                    JiraClient(branchSettings.baseUrl, branchSettings.email, branchSettings.apiToken),
                    GitRepository(workingDirectory),
                ).execute(
                    CreateBranchCommand(
                        projectKey = branchSettings.projectKey,
                        storyNumber = input.storyNumber,
                        name = input.name,
                        storyIssueTypeId = branchSettings.storyIssueTypeId,
                        inProgressStatusId = branchSettings.inProgressStatusId,
                    ),
                )

                val report = BranchReports.of(result)
                JiraTaskNotifications.notify(project, report.message, report.type)
            }
        })
    }
}
