package io.github.gudwns1812.jiratask.ui.mr

import com.intellij.ide.BrowserUtil
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
import com.intellij.openapi.ui.DialogWrapper
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
import io.github.gudwns1812.jiratask.application.MergeRequestPrepare
import io.github.gudwns1812.jiratask.application.MergeRequestProposal
import io.github.gudwns1812.jiratask.application.MergeRequestResult
import io.github.gudwns1812.jiratask.infrastructure.GitLabClient
import io.github.gudwns1812.jiratask.infrastructure.GitRepository
import io.github.gudwns1812.jiratask.ui.JiraTaskAvailability
import io.github.gudwns1812.jiratask.ui.JiraTaskCommand
import io.github.gudwns1812.jiratask.ui.JiraTaskNotifications
import io.github.gudwns1812.jiratask.ui.JiraTaskSettings
import io.github.gudwns1812.jiratask.ui.MergeRequestSettings
import io.github.gudwns1812.jiratask.ui.SettingsLookup
import io.github.gudwns1812.jiratask.ui.applyAvailability
import io.github.gudwns1812.jiratask.ui.commit.CommitIssueKeys
import io.github.gudwns1812.jiratask.ui.task.SprintTasks
import io.github.gudwns1812.jiratask.ui.task.TaskAnswer
import java.io.File
import javax.swing.JComponent

/**
 * 만들 MR을 확인하는 창. 제목·본문·대상 브랜치를 여기서 고칠 수 있다.
 *
 * 연결된 이슈 키는 커밋에서 뽑아 본문에 적어 둔다 — Jira가 MR을 작업에 붙이는 근거다(RULE.md 5.3).
 */
class MergeRequestDialog(
    project: Project?,
    private val proposal: MergeRequestProposal,
) : DialogWrapper(project, false) {

    val titleField: JBTextField = JBTextField(proposal.title)

    val targetField: JBTextField = JBTextField(proposal.targetBranch)

    val descriptionArea: JBTextArea = JBTextArea(proposal.description, 10, 60)

    private val warningLabel = JBLabel(proposal.existingUrl?.let { "이미 열린 MR이 있습니다: $it" }.orEmpty())

    init {
        title = "Create Merge Request"
        setOKButtonText("MR 만들기")
        init()

        listOf(titleField, targetField).forEach { field ->
            field.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(event: javax.swing.event.DocumentEvent) = refreshState()
            })
        }
        refreshState()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("원본 브랜치:") { cell(JBLabel(proposal.sourceBranch)) }
        row("대상 브랜치:") { cell(targetField).columns(COLUMNS_LARGE) }
        row("제목:") { cell(titleField).columns(COLUMNS_LARGE) }
        row("본문:") { cell(JBScrollPane(descriptionArea)).resizableColumn() }
        row {
            cell(JBLabel(issueKeysText()))
        }.rowComment("연결된 작업 키는 본문에 적혀 Jira가 이 MR을 작업에 붙일 수 있게 합니다.")
        row { cell(warningLabel) }
    }

    override fun getPreferredFocusedComponent(): JComponent = titleField

    fun problem(): String? = when {
        titleField.text.isBlank() -> "MR 제목은 비울 수 없습니다."
        targetField.text.isBlank() -> "대상 브랜치를 입력하세요."
        targetField.text.trim() == proposal.sourceBranch -> "원본 브랜치와 대상 브랜치가 같습니다."
        else -> null
    }

    fun values(): MergeRequestProposal = proposal.copy(targetBranch = targetField.text.trim())

    fun warningText(): String = warningLabel.text.orEmpty()

    private fun issueKeysText(): String =
        if (proposal.issueKeys.isEmpty()) NO_ISSUE_KEYS else "연결된 작업: ${proposal.issueKeys.joinToString(", ")}"

    private fun refreshState() {
        isOKActionEnabled = problem() == null
    }

    companion object {
        const val NO_ISSUE_KEYS: String = "커밋에서 Jira 작업 키를 찾지 못했습니다. 본문에 직접 적어 두세요."
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
            is SettingsLookup.Missing -> JiraTaskNotifications.warning(project, ready.message)
            is SettingsLookup.Ready -> prepare(project, workingDirectory, ready.value)
        }
    }

    private fun prepare(project: Project, workingDirectory: File, mr: MergeRequestSettings) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "MR 초안 준비", true) {
            override fun run(indicator: ProgressIndicator) {
                val useCase = useCaseFor(project, mr)
                val prepared = useCase.prepare(readBranch(workingDirectory, useCase))

                ApplicationManager.getApplication().invokeLater({
                    if (project.isDisposed) {
                        return@invokeLater
                    }
                    when (prepared) {
                        is MergeRequestPrepare.Blocked -> JiraTaskNotifications.warning(project, prepared.message)
                        is MergeRequestPrepare.Ready -> confirm(project, mr, prepared.proposal)
                    }
                }, project.disposed)
            }
        })
    }

    private fun confirm(project: Project, mr: MergeRequestSettings, proposal: MergeRequestProposal) {
        val dialog = MergeRequestDialog(project, proposal)
        if (!dialog.showAndGet()) {
            return
        }

        val edited = dialog.values()
        val title = dialog.titleField.text
        val description = dialog.descriptionArea.text

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "MR 생성", false) {
            override fun run(indicator: ProgressIndicator) {
                report(project, useCaseFor(project, mr).create(edited, title, description))
            }
        })
    }

    private fun report(project: Project, result: MergeRequestResult) = when (result) {
        is MergeRequestResult.Created -> JiraTaskNotifications.notify(
            project,
            "MR을 만들었습니다: ${result.webUrl}",
            NotificationType.INFORMATION,
            NotificationAction.createSimple("열기") { BrowserUtil.browse(result.webUrl) },
        )
        is MergeRequestResult.Failure ->
            JiraTaskNotifications.warning(project, "MR을 만들지 못했습니다: ${result.message}")
    }

    private fun useCaseFor(project: Project, mr: MergeRequestSettings): CreateMergeRequestUseCase =
        CreateMergeRequestUseCase(
            gitlab = GitLabClient(mr.gitLabBaseUrl, mr.gitLabToken),
            issueKeysOf = { subjects -> subjects.flatMap(CommitIssueKeys::all).distinct() },
            // 이슈 하나짜리 MR의 제목에 쓴다. 캐시가 모르면 브랜치 이름으로 돌아간다.
            summaryOf = { key -> (SprintTasks.getInstance(project).findCached(key) as? TaskAnswer.Known)?.task?.summary },
            jiraBaseUrl = mr.jiraBaseUrl,
        )

    private fun readBranch(workingDirectory: File, useCase: CreateMergeRequestUseCase): BranchState {
        val git = GitRepository(workingDirectory)
        val branch = git.currentBranch()

        return BranchState(
            branch = branch,
            remoteUrl = git.remoteUrl(),
            isPushed = branch?.let(git::isPushed) ?: false,
            commitSubjects = git.commitSubjectsSince("origin/${CreateMergeRequestUseCase.DEFAULT_TARGET}"),
        )
    }
}
