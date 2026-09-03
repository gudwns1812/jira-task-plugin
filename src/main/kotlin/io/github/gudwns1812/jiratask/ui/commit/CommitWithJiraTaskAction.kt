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

/** 커밋 메시지를 받는 다이얼로그. 이슈 키는 저장된 작업 번호로 붙으므로 본문만 입력한다. */
class CommitMessageDialog(
    project: Project?,
    private val issueKeyHint: String?,
    /**
     * 커밋 메시지를 AI에게 맡긴다. 인자는 사용자가 이미 쓰고 있던 글이다 — 힌트로 함께 보낸다.
     *
     * git과 CLI를 부르므로 EDT에서 부르지 않는다. AI가 꺼져 있어도 버튼은 보인다 —
     * 그때는 이 함수가 이유를 담은 [CommitDraftResult.Refused]를 돌려주고,
     * 그 문구가 곧 "설정에서 켜세요"라는 안내가 된다(MR 창과 같은 판단).
     */
    private val draft: (String?) -> CommitDraftResult = {
        CommitDraftResult.Refused(DraftCommitMessageUseCase.DISABLED)
    },
    private val background: (() -> Unit) -> Unit = {
        ApplicationManager.getApplication().executeOnPooledThread(Runnable(it))
    },
    /**
     * 모달리티를 반드시 [ModalityState.any]로 준다 — 이 창은 모달이라 모달리티를 주지 않으면
     * 풀 스레드에서 넣은 작업이 **창이 닫힌 뒤에야** 실행된다.
     */
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
        // 테마 글꼴에 한글이 없으면 이 창의 글자가 통째로 네모가 된다.
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
            
        // 꺼져 있어도 보인다. 숨기면 기능이 있다는 것도, 켜러 가야 한다는 것도 알 길이 없다.
        row {
            cell(draftButton)
            cell(draftLabel)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = messageArea

    /**
     * 초안을 만들어 입력칸에 넣는다.
     *
     * **사용자가 쓰던 글은 힌트로 보내고 결과로 덮는다.** 자동으로 일어나는 일이 아니라 사용자가
     * 버튼을 눌러 부탁한 일이라, 그대로 두면 부탁한 것이 일어나지 않은 것처럼 보인다.
     */
    fun requestDraft() {
        val hint = messageArea.text?.trim()?.ifEmpty { null }

        // git을 읽기 전에 편집기의 변경을 파일로 내린다. 저장하지 않은 편집은 git에 보이지 않는다.
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
                    // 실패해도 커밋은 막지 않는다. 사용자가 직접 쓰면 된다.
                    is CommitDraftResult.Refused -> draftLabel.text = result.message
                }
            }
        }
    }

    /** AI가 지금 무엇을 말하고 있는지. 화면을 뒤지지 않고 확인하려고 열어 둔다. */
    fun draftText(): String = draftLabel.text.orEmpty()

    // 다이얼로그를 띄우지 않고도 안내 문구를 확인할 수 있게 공개 범위를 넓힌다.
    public override fun doValidate(): ValidationInfo? = problem()?.let { ValidationInfo(it) }

    fun message(): String = messageArea.text.trim()

    fun problem(): String? = if (message().isEmpty()) "커밋 메시지를 입력하세요." else null

    companion object {
        const val DRAFT_BUTTON: String = "AI로 쓰기"
        const val WRITING: String = "AI가 쓰는 중…"
    }
}

/** CLI의 `commit`에 대응하는 액션. */
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

    /** 프로젝트 폴더를 모르면 git을 읽을 수 없다. 그때는 그 이유를 그대로 창에 보여 준다. */
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
