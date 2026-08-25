package io.github.gudwns1812.jiratask.ui.commit

import com.intellij.notification.NotificationAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.panel
import io.github.gudwns1812.jiratask.application.CreateCommitCommand
import io.github.gudwns1812.jiratask.application.CreateCommitUseCase
import io.github.gudwns1812.jiratask.application.CreateCommitResult
import io.github.gudwns1812.jiratask.infrastructure.GitRepository
import io.github.gudwns1812.jiratask.ui.CommitSettings
import io.github.gudwns1812.jiratask.ui.JiraTaskAvailability
import io.github.gudwns1812.jiratask.ui.JiraTaskCommand
import io.github.gudwns1812.jiratask.ui.JiraTaskNotifications
import io.github.gudwns1812.jiratask.ui.JiraTaskSettings
import io.github.gudwns1812.jiratask.ui.SettingsLookup
import io.github.gudwns1812.jiratask.ui.applyAvailability
import io.github.gudwns1812.jiratask.ui.notifyAndRefresh
import io.github.gudwns1812.jiratask.ui.setup.SetupWizardLauncher
import io.github.gudwns1812.jiratask.ui.task.AdvanceCurrentTask
import io.github.gudwns1812.jiratask.ui.task.CurrentTaskState
import io.github.gudwns1812.jiratask.ui.task.TaskTarget
import java.io.File
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/** 커밋 메시지를 받는 다이얼로그. 이슈 키는 저장된 작업 번호로 붙으므로 본문만 입력한다. */
class CommitMessageDialog(project: Project?, private val issueKeyHint: String?) : DialogWrapper(project, false) {

    val messageArea: JBTextArea = JBTextArea(3, 50)

    init {
        title = "Commit with Jira Task"
        setOKButtonText("커밋")
        init()
        messageArea.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                isOKActionEnabled = problem() == null
            }
        })
        isOKActionEnabled = false
    }

    override fun createCenterPanel(): JComponent = panel {
        row("커밋 메시지:") { cell(JBScrollPane(messageArea)) }
            .rowComment(
                issueKeyHint?.let { "커밋 메시지는 '$it <메시지>'로 만들어집니다." }
                    ?: "저장된 작업 번호로 이슈 키를 붙입니다. 작업 번호가 없으면 커밋할 수 없습니다.",
            )
    }

    override fun getPreferredFocusedComponent(): JComponent = messageArea

    // 다이얼로그를 띄우지 않고도 안내 문구를 확인할 수 있게 공개 범위를 넓힌다.
    public override fun doValidate(): ValidationInfo? = problem()?.let { ValidationInfo(it) }

    fun message(): String = messageArea.text.trim()

    fun problem(): String? = if (message().isEmpty()) "커밋 메시지를 입력하세요." else null
}

/** CLI의 `commit`에 대응하는 액션. */
class CommitWithJiraTaskAction @JvmOverloads constructor(
    private val settings: JiraTaskSettings = JiraTaskSettings(),
    private val checkinHandlerFactory: JiraTaskCheckinHandlerFactory = JiraTaskCheckinHandlerFactory(settings),
    private val availability: JiraTaskAvailability = JiraTaskAvailability(),
) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.applyAvailability(availability.of(JiraTaskCommand.COMMIT, event.project?.basePath != null))
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return

        when (val lookup = settings.forCommit()) {
            is SettingsLookup.Missing -> JiraTaskNotifications.error(
                project,
                lookup.message,
                NotificationAction.createSimple("설정 시작") { SetupWizardLauncher.launch(project) },
            )
            is SettingsLookup.Ready -> {
                val dialog = CommitMessageDialog(project, checkinHandlerFactory.issueKeyFor(project))
                if (dialog.showAndGet()) {
                    commit(project, lookup.value, dialog.message())
                }
            }
        }
    }

    private fun commit(project: Project, commitSettings: CommitSettings, message: String) {
        val workingDirectory = project.basePath?.let(::File) ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Jira 작업 번호로 커밋", false) {
            override fun run(indicator: ProgressIndicator) {
                val result = CreateCommitUseCase(GitRepository(workingDirectory))
                    .execute(CreateCommitCommand(commitSettings.projectKey, message))

                val report = CommitReports.of(result)
                notifyAndRefresh(project, report.type, report.message)

                if (result is CreateCommitResult.Success) {
                    CurrentTaskState.getInstance(project).taskNumber?.let { taskNumber ->
                        AdvanceCurrentTask.run(project, taskNumber, TaskTarget.DONE)
                    }
                }
            }
        })
    }
}
