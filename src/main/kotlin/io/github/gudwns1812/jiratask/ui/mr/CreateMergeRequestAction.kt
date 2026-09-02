package io.github.gudwns1812.jiratask.ui.mr

import io.github.gudwns1812.jiratask.ui.applyHangulFallbackToWindow
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import io.github.gudwns1812.jiratask.application.BranchState
import io.github.gudwns1812.jiratask.application.CreateMergeRequestUseCase
import io.github.gudwns1812.jiratask.application.DraftRewrite
import io.github.gudwns1812.jiratask.application.MergeRequestPrepare
import io.github.gudwns1812.jiratask.application.MergeRequestProposal
import io.github.gudwns1812.jiratask.application.MergeRequestResult
import io.github.gudwns1812.jiratask.infrastructure.ClaudeCliDraftWriter
import io.github.gudwns1812.jiratask.infrastructure.GitLabClient
import io.github.gudwns1812.jiratask.infrastructure.GitRepository
import io.github.gudwns1812.jiratask.ui.JiraTaskAvailability
import io.github.gudwns1812.jiratask.ui.JiraTaskCommand
import io.github.gudwns1812.jiratask.ui.JiraTaskNotifications
import io.github.gudwns1812.jiratask.ui.JiraTaskSettings
import io.github.gudwns1812.jiratask.ui.MergeRequestSettings
import io.github.gudwns1812.jiratask.ui.OpenJiraTaskSettingsAction
import io.github.gudwns1812.jiratask.ui.SettingsLookup
import io.github.gudwns1812.jiratask.ui.applyAvailability
import io.github.gudwns1812.jiratask.ui.commit.CommitIssueKeys
import io.github.gudwns1812.jiratask.ui.task.SprintTasks
import io.github.gudwns1812.jiratask.ui.task.TaskAnswer
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.io.File
import javax.swing.JComponent
import javax.swing.JTextField

/**
 * 만들 MR을 확인하는 창. 제목·본문·대상 브랜치를 여기서 고칠 수 있다.
 *
 * 연결된 이슈 키는 커밋에서 뽑아 본문에 적어 둔다 — Jira가 MR을 작업에 붙이는 근거다(RULE.md 5.3).
 */
class MergeRequestDialog(
    project: Project?,
    proposal: MergeRequestProposal,
    /** 대상 브랜치·템플릿을 바꿨을 때 그 기준으로 다시 만든 초안. git을 읽으므로 EDT에서 부르지 않는다. */
    private val redraft: (String, String?) -> MergeRequestProposal = { _, _ -> proposal },
    private val background: (() -> Unit) -> Unit = {
        ApplicationManager.getApplication().executeOnPooledThread(Runnable(it))
    },
    /**
     * 모달리티를 반드시 [ModalityState.any]로 준다.
     *
     * 이 창은 모달이다. 모달리티를 지정하지 않으면 풀 스레드에서 넣은 작업이 `NON_MODAL`로 큐에 들어가
     * **창이 닫힌 뒤에야** 실행된다 — 다시 만든 초안이 화면에 영영 반영되지 않는다.
     */
    private val onEdt: (() -> Unit) -> Unit = {
        ApplicationManager.getApplication().invokeLater(Runnable(it), ModalityState.any())
    },
    /**
     * 제목·본문을 AI에게 다시 쓰게 한다.
     *
     * AI가 꺼져 있어도 버튼은 보인다 — 그때는 이 함수가 이유를 담은 [DraftRewrite.Refused]를 돌려주고,
     * 그 문구가 곧 "설정에서 켜세요"라는 안내가 된다.
     */
    private val rewrite: (MergeRequestProposal) -> DraftRewrite = {
        DraftRewrite.Refused(CreateMergeRequestUseCase.DISABLED)
    },
) : DialogWrapper(project, false) {

    private var proposal: MergeRequestProposal = proposal

    val titleField: JBTextField = JBTextField(proposal.title)

    /**
     * 대상 브랜치. 추측하지 않고 고르게 한다 — 통합 브랜치를 쓰는 팀에서는 저장소 기본 브랜치가
     * 대상인 경우가 오히려 드물다. 목록에 없는 브랜치도 적을 수 있도록 편집을 열어 둔다.
     */
    val targetBox: ComboBox<String> = ComboBox(proposal.targetCandidates.toTypedArray()).apply {
        isEditable = true
        selectedItem = proposal.targetBranch
    }

    /**
     * 쓸 템플릿. 팀이 `.gitlab` 아래에 여러 개를 두는 일이 흔해 고르게 한다 — GitLab 웹과 같다.
     * 목록이 비어 있으면(템플릿이 없으면) 이 줄은 화면에 만들지 않는다.
     */
    val templateBox: ComboBox<String> = ComboBox((listOf(NO_TEMPLATE) + proposal.templates.keys).toTypedArray())
        .apply { selectedItem = proposal.templateName ?: NO_TEMPLATE }

    val descriptionArea: JBTextArea = JBTextArea(proposal.description, 10, 60)

    private val issueKeysLabel = JBLabel()

    private val warningLabel = JBLabel(proposal.existingUrl?.let { "이미 열린 MR이 있습니다: $it" }.orEmpty())

    private val rewriteButton = javax.swing.JButton(REWRITE_BUTTON)

    private val rewriteLabel = JBLabel()

    /** 마지막으로 우리가 만든 초안. 사용자가 직접 고친 글을 덮어쓰지 않으려고 기억해 둔다. */
    private var draftedTitle = proposal.title
    private var draftedDescription = proposal.description

    init {
        title = "Create Merge Request"
        setOKButtonText("MR 만들기")
        init()
        // 테마 글꼴에 한글이 없으면 이 창의 글자가 통째로 네모가 된다.
        applyHangulFallbackToWindow()

        titleField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: javax.swing.event.DocumentEvent) = refreshState()
        })
        targetBox.addActionListener { onTargetChanged() }
        // 목록에서 고르는 것 말고 직접 타이핑하는 경로도 있다. 그쪽은 포커스를 옮길 때 확인한다 —
        // 글자마다 다시 만들면 git을 수십 번 부른다.
        (targetBox.editor.editorComponent as? JTextField)?.addFocusListener(object : FocusAdapter() {
            override fun focusLost(event: FocusEvent) = onTargetChanged()
        })
        templateBox.addActionListener { onTemplateChanged() }
        rewriteButton.addActionListener { onRewrite() }
        showKeys()
        refreshState()
    }

    /**
     * 제목·본문을 AI에게 다시 쓰게 한다.
     *
     * 대상 브랜치를 바꿀 때와 같은 배선을 탄다 — 백그라운드에서 부르고 [onEdt]로 돌아온다.
     * [onEdt]가 `ModalityState.any()`를 쓰는 것이 여기서도 반드시 필요하다. 이 창은 모달이라
     * 모달리티를 주지 않으면 결과 반영이 창 닫힌 뒤로 밀린다.
     *
     * **사용자가 손댄 글은 덮지 않는다** — [apply]가 그 규칙을 들고 있다.
     */
    private fun onRewrite() {
        val current = values()

        rewriteButton.isEnabled = false
        rewriteLabel.text = REWRITING
        background {
            val outcome = runCatching { rewrite(current) }
                .getOrElse { DraftRewrite.Refused(CreateMergeRequestUseCase.FAILED) }

            onEdt {
                rewriteButton.isEnabled = true
                when (outcome) {
                    is DraftRewrite.Rewritten -> {
                        rewriteLabel.text = ""
                        apply(outcome.proposal)
                    }
                    // 실패해도 MR 생성은 막지 않는다. 규칙 기반 초안이 그대로 남아 있다.
                    is DraftRewrite.Refused -> rewriteLabel.text = outcome.message
                }
            }
        }
    }

    /** AI가 지금 무엇을 말하고 있는지. 화면을 뒤지지 않고 확인하려고 열어 둔다. */
    fun rewriteText(): String = rewriteLabel.text.orEmpty()

    override fun createCenterPanel(): JComponent = panel {
        row("원본 브랜치:") { cell(JBLabel(proposal.sourceBranch)) }
        row("대상 브랜치:") {
            cell(targetBox).columns(COLUMNS_LARGE)
        }.rowComment("이 브랜치가 대상 브랜치에 더한 커밋만 본문에 담깁니다. 바꾸면 본문을 다시 만듭니다.")
        if (proposal.templates.isNotEmpty()) {
            row("템플릿:") {
                cell(templateBox).columns(COLUMNS_LARGE)
            }.rowComment("팀 템플릿을 그대로 두고 관련 이슈 절에만 작업을 채웁니다. 그 절이 없으면 뒤에 붙입니다.")
        }
        row("제목:") { cell(titleField).columns(COLUMNS_LARGE) }
        row("본문:") { cell(JBScrollPane(descriptionArea)).resizableColumn() }
        row {
            cell(issueKeysLabel)
        }.rowComment("연결된 작업 키는 본문에 적혀 Jira가 이 MR을 작업에 붙일 수 있게 합니다.")
        // 꺼져 있어도 보인다. 숨기면 기능이 있다는 것도, 켜러 가야 한다는 것도 알 길이 없다.
        row {
            cell(rewriteButton)
            cell(rewriteLabel)
        }.rowComment("브랜치 이름·커밋 제목·변경된 파일 경로만 보냅니다. 파일 내용은 보내지 않습니다.")
        row { cell(warningLabel) }
    }

    override fun getPreferredFocusedComponent(): JComponent = titleField

    fun target(): String = (targetBox.editor.item ?: targetBox.selectedItem)?.toString()?.trim().orEmpty()

    /**
     * 무엇이 잘못됐는지와 어느 칸을 가리켜야 하는지.
     *
     * [doValidate]로 내보내야 화면에 실제로 뜬다 — 버튼만 꺼 두면 사용자는 이유 없이 죽은 버튼을 본다.
     */
    override fun doValidate(): ValidationInfo? = when {
        titleField.text.isBlank() -> ValidationInfo("MR 제목은 비울 수 없습니다.", titleField)
        target().isBlank() -> ValidationInfo("대상 브랜치를 고르세요.", targetBox)
        target() == proposal.sourceBranch -> ValidationInfo("원본 브랜치와 대상 브랜치가 같습니다.", targetBox)
        else -> null
    }

    fun problem(): String? = doValidate()?.message

    fun values(): MergeRequestProposal = proposal.copy(targetBranch = target())

    fun warningText(): String = warningLabel.text.orEmpty()

    /** 템플릿을 바꾸면 그 템플릿으로 본문을 다시 만든다. 대상 브랜치를 바꿀 때와 같은 배선이다. */
    private fun onTemplateChanged() {
        val chosen = templateBox.selectedItem?.toString()?.takeIf { it != NO_TEMPLATE }
        if (chosen == proposal.templateName) {
            return
        }
        redraftWith(target(), chosen)
    }

    /**
     * 대상이 바뀌면 그 기준으로 초안을 다시 만든다.
     *
     * 사용자가 손댄 제목·본문은 그대로 둔다 — 브랜치를 잘못 골랐다가 되돌리는 동안 쓰던 글이
     * 사라지면 안 된다. 우리가 만든 글 그대로일 때만 갈아 끼운다.
     */
    private fun onTargetChanged() {
        val target = target()
        if (target.isBlank() || target == proposal.targetBranch) {
            refreshState()
            return
        }
        redraftWith(target, proposal.templateName)
    }

    /** 대상 브랜치나 템플릿이 바뀌면 그 조합으로 초안을 다시 만든다. */
    private fun redraftWith(target: String, templateName: String?) {
        background {
            val next = runCatching { redraft(target, templateName) }.getOrNull() ?: return@background
            onEdt {
                if (target != target()) {
                    // 그새 또 바꿨다. 지금 화면과 맞지 않는 결과는 버린다.
                    return@onEdt
                }
                apply(next)
            }
        }
    }

    private fun apply(next: MergeRequestProposal) {
        if (titleField.text == draftedTitle) {
            titleField.text = next.title
        }
        if (descriptionArea.text == draftedDescription) {
            descriptionArea.text = next.description
        }
        draftedTitle = next.title
        draftedDescription = next.description
        proposal = next
        showKeys()
        refreshState()
    }

    private fun showKeys() {
        issueKeysLabel.text = if (proposal.issueKeys.isEmpty()) {
            NO_ISSUE_KEYS
        } else {
            "연결된 작업: ${proposal.issueKeys.joinToString(", ")}"
        }
    }

    private fun refreshState() {
        isOKActionEnabled = problem() == null
    }

    companion object {
        const val NO_ISSUE_KEYS: String = "커밋에서 Jira 작업 키를 찾지 못했습니다. 본문에 직접 적어 두세요."
        const val REWRITE_BUTTON: String = "AI로 다시 쓰기"
        const val NO_TEMPLATE: String = "템플릿 없음"
        const val REWRITING: String = "AI가 쓰는 중…"
    }
}

/** 현재 브랜치의 커밋에서 이슈 키를 모아 GitLab MR을 만든다(RULE.md 5.3). */
class CreateMergeRequestAction @JvmOverloads constructor(
    private val settings: JiraTaskSettings = JiraTaskSettings(),
    private val availability: JiraTaskAvailability = JiraTaskAvailability(),
) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.applyAvailability(availability.of(JiraTaskCommand.MERGE_REQUEST, event.project?.basePath != null))
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val workingDirectory = project.basePath?.let(::File) ?: return

        when (val ready = settings.forMergeRequest()) {
            // 막힌 자리에서 바로 고치러 갈 수 있게 한다. 설정 화면에 이르는 다른 길이 사실상 없다.
            is SettingsLookup.Missing -> JiraTaskNotifications.warning(
                project,
                ready.message,
                OpenJiraTaskSettingsAction.notificationAction(project),
            )
            is SettingsLookup.Ready -> prepare(project, workingDirectory, ready.value)
        }
    }

    private fun prepare(project: Project, workingDirectory: File, mr: MergeRequestSettings) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "MR 초안 준비", true) {
            override fun run(indicator: ProgressIndicator) {
                val useCase = useCaseFor(project, mr, workingDirectory)
                // 상태는 한 번만 읽는다. 대상 브랜치를 바꿔 다시 계산할 때도 같은 것을 쓴다.
                val state = readBranch(workingDirectory)
                val prepared = useCase.prepare(state)

                ApplicationManager.getApplication().invokeLater({
                    if (project.isDisposed) {
                        return@invokeLater
                    }
                    when (prepared) {
                        is MergeRequestPrepare.Blocked -> {
                            thisLogger().info("MR 초안을 만들 수 없음: ${prepared.message}")
                            JiraTaskNotifications.warning(project, prepared.message)
                        }
                        is MergeRequestPrepare.Ready ->
                            confirm(
                                project = project,
                                mr = mr,
                                workingDirectory = workingDirectory,
                                proposal = prepared.proposal,
                                redraft = { target, templateName ->
                                    useCase.redraft(state, prepared.proposal, target, templateName)
                                },
                                rewrite = { current -> useCase.rewrite(state, current) },
                            )
                    }
                }, project.disposed)
            }
        })
    }

    private fun confirm(
        project: Project,
        mr: MergeRequestSettings,
        workingDirectory: File,
        proposal: MergeRequestProposal,
        redraft: (String, String?) -> MergeRequestProposal,
        rewrite: (MergeRequestProposal) -> DraftRewrite,
    ) {
        val dialog = MergeRequestDialog(project, proposal, redraft, rewrite = rewrite)
        if (!dialog.showAndGet()) {
            return
        }

        val edited = dialog.values()
        val title = dialog.titleField.text
        val description = dialog.descriptionArea.text

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "MR 생성", false) {
            override fun run(indicator: ProgressIndicator) {
                report(project, useCaseFor(project, mr, workingDirectory).create(edited, title, description))
            }
        })
    }

    private fun report(project: Project, result: MergeRequestResult) = when (result) {
        // 주소를 못 읽었어도 MR은 만들어졌다. 실패로 알리면 사용자가 같은 MR을 한 번 더 만들려 든다.
        is MergeRequestResult.Created -> result.webUrl?.let { url ->
            JiraTaskNotifications.notify(
                project,
                "MR을 만들었습니다: $url",
                NotificationType.INFORMATION,
                NotificationAction.createSimple("열기") { BrowserUtil.browse(url) },
            )
        } ?: JiraTaskNotifications.notify(
            project,
            "MR을 만들었습니다. 다만 GitLab이 주소를 알려주지 않아 여기서 열 수는 없습니다.",
            NotificationType.INFORMATION,
        )
        is MergeRequestResult.Failure -> {
            // 알림은 사라진다. 실패는 로그에도 남겨야 나중에 원인을 되짚을 수 있다.
            // 토큰은 절대 적지 않는다 — 메시지에는 출처 이름만 들어 있다(ARCHITECTURE.md 191, 226).
            thisLogger().warn("MR 생성 실패: ${result.message}")
            JiraTaskNotifications.warning(project, "MR을 만들지 못했습니다: ${result.message}")
        }
    }

    private fun useCaseFor(
        project: Project,
        mr: MergeRequestSettings,
        workingDirectory: File,
    ): CreateMergeRequestUseCase =
        CreateMergeRequestUseCase(
            gitlab = GitLabClient(mr.gitLabBaseUrl, mr.gitLabToken),
            issueKeysOf = { subjects -> issueKeysIn(subjects) },
            // 이슈 하나짜리 MR의 제목에 쓴다. 캐시가 모르면 브랜치 이름으로 돌아간다.
            summaryOf = { key -> (SprintTasks.getInstance(project).findCached(key) as? TaskAnswer.Known)?.task?.summary },
            gitLabBaseUrl = mr.gitLabBaseUrl,
            tokenOrigin = mr.tokenSource.describe(),
            // 꺼져 있으면 아예 만들지 않는다 — 그래야 다이얼로그에 버튼도 나오지 않는다.
            draftWriter = draftWriterFor(workingDirectory),
        )

    /** 설정에서 AI를 켰을 때만 만든다. 기본값은 꺼짐이다. */
    private fun draftWriterFor(workingDirectory: File): ClaudeCliDraftWriter? {
        val ai = (settings.forAiDraft() as? SettingsLookup.Ready)?.value ?: return null

        return ClaudeCliDraftWriter(
            workingDirectory = workingDirectory,
            executablePath = ai.executablePath,
            model = ai.model,
        )
    }

    /**
     * 커밋 제목에서 **이 프로젝트의** 이슈 키만 모은다.
     *
     * `대문자-숫자`는 Jira 키 말고도 흔하다 — 팀이 쓰는 명세 번호(`API-04`, `GAME-05`)가 그대로 걸린다.
     * 그런 키로 만든 Jira 링크는 열리지 않으므로 설정된 프로젝트 키로 거른다.
     * 프로젝트 키를 모를 때만 거르지 않는다 — 아는 게 없으면 덜어내는 것도 추측이다.
     */
    private fun issueKeysIn(subjects: List<String>): List<String> {
        val found = subjects.flatMap(CommitIssueKeys::all).distinct()
        val projectKey = (settings.forCommit() as? SettingsLookup.Ready)?.value?.projectKey ?: return found

        return found.filter { it.startsWith("$projectKey-") }
    }

    private fun readBranch(workingDirectory: File): BranchState {
        val git = GitRepository(workingDirectory)
        val branch = git.currentBranch()

        return BranchState(
            branch = branch,
            remoteUrl = git.remoteUrl(),
            isPushed = branch?.let(git::isPushed),
            // 대상 브랜치가 정해진 뒤에 불린다. 그 브랜치의 원격 ref가 없으면 있는 기본 브랜치를 찾아 쓴다.
            commitSubjectsSince = { target -> git.baseRef(target)?.let(git::commitSubjectsSince) ?: emptyList() },
            remoteBranches = git.remoteBranches(),
            changedFilesSince = { target -> git.baseRef(target)?.let(git::changedFilesSince) ?: emptyList() },
            templates = git.mergeRequestTemplates(),
        )
    }
}
