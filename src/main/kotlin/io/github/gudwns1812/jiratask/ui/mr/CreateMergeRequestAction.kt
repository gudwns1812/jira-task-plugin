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
import com.intellij.ui.dsl.builder.AlignY
import io.github.gudwns1812.jiratask.domain.mr.DraftWriter
import io.github.gudwns1812.jiratask.domain.mr.GitLabUser
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
import io.github.gudwns1812.jiratask.infrastructure.GitLabClient
import io.github.gudwns1812.jiratask.infrastructure.GitRepository
import io.github.gudwns1812.jiratask.ui.JiraTaskAvailability
import io.github.gudwns1812.jiratask.ui.JiraTaskCommand
import io.github.gudwns1812.jiratask.ui.JiraTaskNotifications
import io.github.gudwns1812.jiratask.ui.AiWriters
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

class MergeRequestDialog(
    project: Project?,
    proposal: MergeRequestProposal,
    private val redraft: (String, String?) -> MergeRequestProposal = { _, _ -> proposal },
    private val background: (() -> Unit) -> Unit = {
        ApplicationManager.getApplication().executeOnPooledThread(Runnable(it))
    },
    private val onEdt: (() -> Unit) -> Unit = {
        ApplicationManager.getApplication().invokeLater(Runnable(it), ModalityState.any())
    },
    private val rewrite: (MergeRequestProposal) -> DraftRewrite = {
        DraftRewrite.Refused(CreateMergeRequestUseCase.DISABLED)
    },
    private val candidates: List<GitLabUser> = proposal.availableReviewers,
    private val findUsers: (String) -> List<GitLabUser> = { emptyList() },
) : DialogWrapper(project, false) {

    private var proposal: MergeRequestProposal = proposal

    val titleField: JBTextField = JBTextField(proposal.title)

    val targetBox: ComboBox<String> = ComboBox(proposal.targetCandidates.toTypedArray()).apply {
        isEditable = true
        selectedItem = proposal.targetBranch
    }

    val templateBox: ComboBox<String> = ComboBox((listOf(NO_TEMPLATE) + proposal.templates.keys).toTypedArray())
        .apply { selectedItem = proposal.templateName ?: NO_TEMPLATE }

    val descriptionArea: JBTextArea = JBTextArea(proposal.description, 10, 60)

    private val assigneesLabel = JBLabel()

    private val reviewersLabel = JBLabel()

    private val labelsLabel = JBLabel()

    private var assignees: List<GitLabUser> = proposal.assignees

    private var reviewers: List<GitLabUser> = proposal.reviewers

    private var labels: List<String> = proposal.labels

    private val issueKeysLabel = JBLabel()

    private val warningLabel = JBLabel(proposal.existingUrl?.let { "이미 열린 MR이 있습니다: $it" }.orEmpty())

    private val rewriteButton = javax.swing.JButton(REWRITE_BUTTON)

    private val rewriteLabel = JBLabel()

    private var draftedTitle = proposal.title
    private var draftedDescription = proposal.description

    init {
        title = "Create Merge Request"
        setOKButtonText("MR 만들기")
        init()
        applyHangulFallbackToWindow()

        titleField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: javax.swing.event.DocumentEvent) = refreshState()
        })
        targetBox.addActionListener { onTargetChanged() }
        (targetBox.editor.editorComponent as? JTextField)?.addFocusListener(object : FocusAdapter() {
            override fun focusLost(event: FocusEvent) = onTargetChanged()
        })
        templateBox.addActionListener { onTemplateChanged() }
        rewriteButton.addActionListener { onRewrite() }
        showPeople()
        showKeys()
        refreshState()
    }

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
                    is DraftRewrite.Refused -> rewriteLabel.text = outcome.message
                }
            }
        }
    }

    fun rewriteText(): String = rewriteLabel.text.orEmpty()

    override fun createCenterPanel(): JComponent = panel {
        row {
            panel {
                row("원본 브랜치:") { cell(JBLabel(proposal.sourceBranch)) }
                row("대상 브랜치:") {
                    cell(targetBox).columns(COLUMNS_LARGE)
                }
                if (proposal.templates.isNotEmpty()) {
                    row("템플릿:") {
                        cell(templateBox).columns(COLUMNS_LARGE)
                    }
                }
            }.align(AlignY.TOP)

            panel {
                row("담당자:") {
                    cell(assigneesLabel)
                    button(PICK) { pickAssignee() }
                }
                row("리뷰어:") {
                    cell(reviewersLabel)
                    button(PICK) { pickReviewers() }
                }
                row("라벨:") {
                    cell(labelsLabel)
                    button(PICK) { pickLabels() }
                }
            }.align(AlignY.TOP)
        }
        row("제목:") { cell(titleField).columns(COLUMNS_LARGE) }
        row("본문:") { cell(JBScrollPane(descriptionArea)).resizableColumn() }
        row {
            cell(issueKeysLabel)
        }
        row {
            cell(rewriteButton)
            cell(rewriteLabel)
        }
        row { cell(warningLabel) }
    }

    override fun getPreferredFocusedComponent(): JComponent = titleField

    private fun pickAssignee() {
        val dialog = MultiPickDialog(
            project = null,
            dialogTitle = "담당자 고르기",
            items = candidates,
            selected = assignees,
            labelOf = GitLabUser::label,
            search = findUsers,
            single = true,
        )
        if (dialog.showAndGet()) {
            assignees = dialog.picked().take(1)
            showPeople()
        }
    }

    private fun pickReviewers() {
        val dialog = MultiPickDialog(
            project = null,
            dialogTitle = "리뷰어 고르기",
            items = candidates,
            selected = reviewers,
            labelOf = GitLabUser::label,
            search = findUsers,
        )
        if (dialog.showAndGet()) {
            reviewers = dialog.picked()
            showPeople()
        }
    }

    private fun pickLabels() {
        val dialog = MultiPickDialog(
            project = null,
            dialogTitle = "라벨 고르기",
            items = proposal.availableLabels,
            selected = labels,
            labelOf = { it },
        )
        if (dialog.showAndGet()) {
            labels = dialog.picked()
            showPeople()
        }
    }

    private fun showPeople() {
        assigneesLabel.text = assignees.joinToString(", ") { it.label() }.ifEmpty { NONE }
        reviewersLabel.text = reviewers.joinToString(", ") { it.label() }.ifEmpty { NONE }
        labelsLabel.text = labels.joinToString(", ").ifEmpty { NONE }
    }

    fun assignees(): List<GitLabUser> = assignees

    fun reviewers(): List<GitLabUser> = reviewers

    fun labels(): List<String> = labels

    fun target(): String = (targetBox.editor.item ?: targetBox.selectedItem)?.toString()?.trim().orEmpty()

    override fun doValidate(): ValidationInfo? = when {
        titleField.text.isBlank() -> ValidationInfo("MR 제목은 비울 수 없습니다.", titleField)
        target().isBlank() -> ValidationInfo("대상 브랜치를 고르세요.", targetBox)
        target() == proposal.sourceBranch -> ValidationInfo("원본 브랜치와 대상 브랜치가 같습니다.", targetBox)
        else -> null
    }

    fun problem(): String? = doValidate()?.message

    fun values(): MergeRequestProposal = proposal.copy(
        targetBranch = target(),
        assignees = assignees,
        reviewers = reviewers,
        labels = labels,
    )

    fun warningText(): String = warningLabel.text.orEmpty()

    private fun onTemplateChanged() {
        val chosen = templateBox.selectedItem?.toString()?.takeIf { it != NO_TEMPLATE }
        if (chosen == proposal.templateName) {
            return
        }
        redraftWith(target(), chosen)
    }

    private fun onTargetChanged() {
        val target = target()
        if (target.isBlank() || target == proposal.targetBranch) {
            refreshState()
            return
        }
        redraftWith(target, proposal.templateName)
    }

    private fun redraftWith(target: String, templateName: String?) {
        background {
            val next = runCatching { redraft(target, templateName) }.getOrNull() ?: return@background
            onEdt {
                if (target != target()) {
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
        const val PICK: String = "고르기"
        const val NONE: String = "없음"
        const val NO_ISSUE_KEYS: String = "커밋에서 Jira 작업 키를 찾지 못했습니다. 본문에 직접 적어 두세요."
        const val REWRITE_BUTTON: String = "AI로 다시 쓰기"
        const val NO_TEMPLATE: String = "템플릿 없음"
        const val REWRITING: String = "AI가 쓰는 중…"
    }
}

class CreateMergeRequestAction @JvmOverloads constructor(
    private val settings: JiraTaskSettings = JiraTaskSettings(),
    private val availability: JiraTaskAvailability = JiraTaskAvailability(),
    private val ai: AiWriters = AiWriters(settings),
) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.applyAvailability(availability.of(JiraTaskCommand.MERGE_REQUEST, event.project?.basePath != null))
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val workingDirectory = project.basePath?.let(::File) ?: return

        when (val ready = settings.forMergeRequest()) {
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
                                findUsers = { query ->
                                    GitLabClient(mr.gitLabBaseUrl, mr.gitLabToken)
                                        .findUsers(prepared.proposal.projectPath, query)
                                },
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
        findUsers: (String) -> List<GitLabUser>,
    ) {
        val dialog = MergeRequestDialog(project, proposal, redraft, rewrite = rewrite, findUsers = findUsers)
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
            summaryOf = { key -> (SprintTasks.getInstance(project).findCached(key) as? TaskAnswer.Known)?.task?.summary },
            gitLabBaseUrl = mr.gitLabBaseUrl,
            tokenOrigin = mr.tokenSource.describe(),
            draftWriter = draftWriterFor(workingDirectory),
            defaultReviewers = mr.defaultReviewers,
        )

    private fun draftWriterFor(workingDirectory: File): DraftWriter? = ai.of(workingDirectory)?.draftWriter

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
            commitSubjectsSince = { target -> git.baseRef(target)?.let(git::commitSubjectsSince) ?: emptyList() },
            remoteBranches = git.remoteBranches(),
            changedFilesSince = { target -> git.baseRef(target)?.let(git::changedFilesSince) ?: emptyList() },
            templates = git.mergeRequestTemplates(),
        )
    }
}
