package io.github.gudwns1812.jiratask.ui.commit

import io.github.gudwns1812.jiratask.ui.applyHangulFallbackToWindow
import com.intellij.notification.NotificationAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.panel
import io.github.gudwns1812.jiratask.application.CommitDraftResult
import io.github.gudwns1812.jiratask.application.CreateCommitCommand
import io.github.gudwns1812.jiratask.application.CreateCommitUseCase
import io.github.gudwns1812.jiratask.application.DraftCommitMessageUseCase
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
import java.io.File
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

class CommitMessageDialog(
    project: Project?,
    private val issueKeyHint: String?,
    private val draft: (String?) -> CommitDraftResult = {
        CommitDraftResult.Refused(DraftCommitMessageUseCase.DISABLED)
    },
    private val background: (() -> Unit) -> Unit = {
        ApplicationManager.getApplication().executeOnPooledThread(Runnable(it))
    },
    private val onEdt: (() -> Unit) -> Unit = {
        ApplicationManager.getApplication().invokeLater(Runnable(it), ModalityState.any())
    },
) : DialogWrapper(project, false) {

    val messageArea: JBTextArea = JBTextArea(3, 50)

    private val draftButton = JButton(DRAFT_BUTTON)

    private val draftLabel = JBLabel()

    init {
        title = "Commit with Jira Task"
        setOKButtonText("커밋")
        init()
        applyHangulFallbackToWindow()
        messageArea.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                isOKActionEnabled = problem() == null
            }
        })
        draftButton.addActionListener { requestDraft() }
        isOKActionEnabled = false
    }

    override fun createCenterPanel(): JComponent = panel {
        row("커밋 메시지:") { cell(JBScrollPane(messageArea)) }
            
        row {
            cell(draftButton)
            cell(draftLabel)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = messageArea

    fun requestDraft() {
        val hint = messageArea.text?.trim()?.ifEmpty { null }

        CommitDrafts.saveEditedFiles()

        draftButton.isEnabled = false
        draftLabel.text = WRITING
        background {
            val result = runCatching { draft(hint) }
                .getOrElse { CommitDraftResult.Refused(DraftCommitMessageUseCase.FAILED) }

            onEdt {
                draftButton.isEnabled = true
                when (result) {
                    is CommitDraftResult.Drafted -> {
                        draftLabel.text = ""
                        messageArea.text = result.message.subject
                    }
                    is CommitDraftResult.Refused -> draftLabel.text = result.message
                }
            }
        }
    }

    fun draftText(): String = draftLabel.text.orEmpty()

    public override fun doValidate(): ValidationInfo? = problem()?.let { ValidationInfo(it) }

    fun message(): String = messageArea.text.trim()

    fun problem(): String? = if (message().isEmpty()) "커밋 메시지를 입력하세요." else null

    companion object {
        const val DRAFT_BUTTON: String = "AI로 쓰기"
        const val WRITING: String = "AI가 쓰는 중…"
    }
}

class CommitWithJiraTaskAction @JvmOverloads constructor(
    private val settings: JiraTaskSettings = JiraTaskSettings(),
    private val checkinHandlerFactory: JiraTaskCheckinHandlerFactory = JiraTaskCheckinHandlerFactory(settings),
    private val availability: JiraTaskAvailability = JiraTaskAvailability(),
    private val drafts: CommitDrafts = CommitDrafts(settings),
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
                val dialog = CommitMessageDialog(
                    project,
                    checkinHandlerFactory.issueKeyFor(project),
                    draft = draftFor(project),
                )
                if (dialog.showAndGet()) {
                    commit(project, lookup.value, dialog.message())
                }
            }
        }
    }

    private fun draftFor(project: Project): (String?) -> CommitDraftResult {
        val workingDirectory = project.basePath?.let(::File)
            ?: return { CommitDraftResult.Refused(DraftCommitMessageUseCase.NO_GIT) }

        return { hint -> drafts.draft(project, workingDirectory, hint) }
    }

    private fun commit(project: Project, commitSettings: CommitSettings, message: String) {
        val workingDirectory = project.basePath?.let(::File) ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Jira 작업 번호로 커밋", false) {
            override fun run(indicator: ProgressIndicator) {
                val result = CreateCommitUseCase(GitRepository(workingDirectory))
                    .execute(CreateCommitCommand(commitSettings.projectKey, message))

                val report = CommitReports.of(result)
                notifyAndRefresh(project, report.type, report.message)
            }
        })
    }
}
