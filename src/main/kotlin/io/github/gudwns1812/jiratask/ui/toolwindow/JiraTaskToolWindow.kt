package io.github.gudwns1812.jiratask.ui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.panel
import io.github.gudwns1812.jiratask.ui.applyHangulFallback
import io.github.gudwns1812.jiratask.domain.jira.EmptySprintReason
import io.github.gudwns1812.jiratask.ui.task.SprintTaskGroup
import io.github.gudwns1812.jiratask.ui.task.SprintTaskList
import io.github.gudwns1812.jiratask.ui.task.SprintTaskLoad
import io.github.gudwns1812.jiratask.ui.task.SprintTaskRow
import io.github.gudwns1812.jiratask.ui.task.SprintTasks
import io.github.gudwns1812.jiratask.ui.task.StartTaskAction
import io.github.gudwns1812.jiratask.ui.task.StartTaskChoice
import io.github.gudwns1812.jiratask.ui.task.TaskAnswer
import io.github.gudwns1812.jiratask.application.EditSettingsUseCase
import io.github.gudwns1812.jiratask.infrastructure.GitRepository
import io.github.gudwns1812.jiratask.infrastructure.PolicyConfigRepository
import io.github.gudwns1812.jiratask.ui.JiraTaskConfigurable
import io.github.gudwns1812.jiratask.ui.JiraTaskStateListener
import io.github.gudwns1812.jiratask.ui.setup.SetupWizardLauncher
import io.github.gudwns1812.jiratask.ui.task.CurrentTaskState
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel

class JiraTaskToolWindowPanel @JvmOverloads constructor(
    private val project: Project,
    parentDisposable: Disposable,
    private val browse: (String) -> Unit = { BrowserUtil.browse(it) },
    private val launchWizard: () -> Unit = { SetupWizardLauncher.launch(project) },
    private val openSettings: () -> Unit = {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, JiraTaskConfigurable::class.java)
    },
    private val boardReader: (() -> JiraTaskBoard)? = null,
    private val readSprint: (Boolean) -> SprintTaskLoad = { force ->
        SprintTasks.getInstance(project).let { if (force) it.reload() else it.current() }
    },
    private val taskNumberFromGit: () -> String? = {
        project.basePath?.let { runCatching { GitRepository(File(it)).getCurrentTaskNumber() }.getOrNull() }
    },
) : SimpleToolWindowPanel(true, true) {
    enum class Shown { STATUS, SETUP_NEEDED, PROBLEM }

    private val repository = PolicyConfigRepository()

    private val rowViews: Map<String, StatusRowView> = JiraTaskStatus.EMPTY.settingsRows()
        .associate { it.name to StatusRowView.of(it.kind, browse) }

    private val problemLabel = JBLabel()

    private val taskKeyLabel = ActionLink("") { openCurrentIssue() }

    private val taskSummaryLabel = JBLabel()

    private val taskStatusLabel = JBLabel()

    private val branchLabel = JBLabel()

    private val sprintModel = DefaultListModel<SprintTaskRow>()

    val sprintList: JBList<SprintTaskRow> = JBList(sprintModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = SprintRowRenderer()
        ListSpeedSearch.installOn(this) { row -> (row as? SprintTaskRow.Task)?.task?.label() }
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2) {
                    startSelected()
                }
            }
        })
    }

    private val sprintNoticeLabel = JBLabel()

    private var board: JiraTaskBoard = JiraTaskBoard.EMPTY

    private val currentTaskPanel: JComponent = panel {
        group("현재 작업") {
            row("Jira 키:") { cell(taskKeyLabel) }
            row("제목:") { cell(taskSummaryLabel) }
            row("상태:") { cell(taskStatusLabel) }
            row("브랜치:") { cell(branchLabel) }
        }
    }

    private val sprintPanel: JComponent = panel {
        collapsibleGroup("현재 스프린트") {
            row { cell(JBScrollPane(sprintList)).resizableColumn() }
            row { cell(sprintNoticeLabel) }
            row {
                link(START_TASK) { startSelected() }
                link(REFRESH) { refreshInBackground(force = true) }
            }
        }.expanded = true
    }

    private val settingsPanel: JComponent = panel {
        collapsibleGroup("설정 요약") {
            JiraTaskStatus.EMPTY.settingsRows().forEach { statusRow ->
                row("${statusRow.name}:") { cell(rowViews.getValue(statusRow.name).component) }
            }
        }.expanded = false
    }

    private val statusPanel: JComponent = panel {
        row { cell(currentTaskPanel) }
        row { cell(sprintPanel).resizableColumn() }
        row { cell(settingsPanel) }
    }

    private val setupNeededPanel: JComponent = panel {
        row {
            icon(AllIcons.General.Information)
            label(JiraTaskStatus.NEEDS_SETUP)
        }
        row {
            link(RUN_WIZARD) { launchWizard() }
            link(OPEN_SETTINGS) { openSettings() }
        }
    }

    private val problemPanel: JComponent = panel {
        row {
            icon(AllIcons.General.Warning)
            cell(problemLabel)
        }
        row {
            link(OPEN_SETTINGS) { openSettings() }
        }
    }

    private val body: JComponent = panel {
        row { cell(statusPanel).resizableColumn() }
        row { cell(setupNeededPanel) }
        row { cell(problemPanel) }
    }

    init {
        toolbar = createToolbar().component
        setContent(JBScrollPane(body))
        body.applyHangulFallback()
        show(JiraTaskBoard.EMPTY)
        subscribe(parentDisposable)
    }

    private fun subscribe(parentDisposable: Disposable) {
        val connection = project.messageBus.connect(parentDisposable)
        connection.subscribe(JiraTaskStateListener.TOPIC, JiraTaskStateListener { refreshInBackground() })
        connection.subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(toolWindow: ToolWindow) = onToolWindowShown(toolWindow.id)
            },
        )
    }

    fun onToolWindowShown(toolWindowId: String) {
        if (toolWindowId == TOOL_WINDOW_ID) {
            refreshInBackground()
        }
    }

    fun titleActions(): List<AnAction> = listOf(RefreshStatusAction(), OpenSettingsAction())

    fun currentStatus(): JiraTaskStatus = board.status

    fun currentBoard(): JiraTaskBoard = board

    fun shown(): Shown = when {
        setupNeededPanel.isVisible -> Shown.SETUP_NEEDED
        problemPanel.isVisible -> Shown.PROBLEM
        else -> Shown.STATUS
    }

    @JvmOverloads
    fun refreshInBackground(force: Boolean = false) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val next = boardReader?.invoke() ?: readBoard(force)
            ApplicationManager.getApplication().invokeLater({ show(next) }) { project.isDisposed }
        }
    }

    @JvmOverloads
    fun readBoard(force: Boolean = false): JiraTaskBoard {
        val load = EditSettingsUseCase(repository::loadConfig, repository::saveConfig).load()
        val git = project.basePath?.let { GitRepository(File(it)) }
        val state = CurrentTaskState.getInstance(project)
        val taskNumber = state.taskNumber ?: taskNumberFromGit()?.also(state::apply)
        val status = JiraTaskStatus.of(load, taskNumber, git?.currentBranch())

        if (!status.isConfigured) {
            return JiraTaskBoard(status)
        }

        val sprint = readSprint(force)
        val currentTask = status.currentIssueKey()?.let { key ->
            (sprint as? SprintTaskLoad.Loaded)?.tasks?.firstOrNull { it.key == key }
                ?: (SprintTasks.getInstance(project).find(key) as? TaskAnswer.Known)?.task
        }

        return JiraTaskBoard(status, currentTask, sprint)
    }

    fun show(next: JiraTaskBoard) {
        board = next
        next.status.settingsRows().forEach { statusRow -> rowViews[statusRow.name]?.show(statusRow) }
        problemLabel.text = next.status.problem?.message.orEmpty()
        showCurrentTask(next)
        showSprint(next.sprint)

        val kind = next.status.problem?.kind
        statusPanel.isVisible = kind == null
        setupNeededPanel.isVisible = kind == StatusProblem.Kind.NEEDS_SETUP
        problemPanel.isVisible = kind == StatusProblem.Kind.BROKEN
    }

    private fun showCurrentTask(next: JiraTaskBoard) {
        val issueKey = next.status.currentIssueKey()
        taskKeyLabel.text = issueKey ?: JiraTaskStatus.NO_TASK
        taskKeyLabel.isEnabled = issueKey != null && next.status.issueUrl(issueKey) != null
        taskSummaryLabel.text = if (issueKey == null) START_HINT else next.currentTaskTitle()
        taskStatusLabel.text = next.currentTask?.statusName?.takeIf { it.isNotBlank() } ?: UNKNOWN_VALUE
        branchLabel.text = next.status.branch ?: JiraTaskStatus.NO_BRANCH
    }

    private fun showSprint(sprint: SprintTaskLoad?) {
        sprintModel.clear()
        sprintNoticeLabel.text = when (sprint) {
            null -> SPRINT_LOADING
            is SprintTaskLoad.Loaded -> {
                SprintTaskList.rowsOf(sprint.tasks).forEach(sprintModel::addElement)
                if (sprint.tasks.isEmpty()) {
                    (sprint.reason ?: EmptySprintReason.UNKNOWN).message
                } else {
                    SprintTaskList.excludedNotice(sprint.otherAssigneeCount)
                }
            }
            is SprintTaskLoad.NotConfigured -> sprint.message
            is SprintTaskLoad.Failed -> sprint.message
        }
    }

    fun startSelected() {
        val task = (sprintList.selectedValue as? SprintTaskRow.Task)?.task ?: return
        StartTaskAction.start(project, StartTaskChoice(task.key.substringAfterLast('-'), task))
    }

    private fun openCurrentIssue() {
        val issueKey = board.status.currentIssueKey() ?: return
        board.status.issueUrl(issueKey)?.let(browse)
    }

    private inner class SprintRowRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val row = value as? SprintTaskRow
            val text = when (row) {
                is SprintTaskRow.Header -> row.group.title
                is SprintTaskRow.Task -> "    " + row.task.label()
                null -> ""
            }
            val selectable = row is SprintTaskRow.Task
            val component = super.getListCellRendererComponent(list, text, index, isSelected && selectable, cellHasFocus)

            if (row is SprintTaskRow.Header) {
                font = font.deriveFont(java.awt.Font.BOLD)
            } else if (row is SprintTaskRow.Task && row.group == SprintTaskGroup.DONE && !isSelected) {
                foreground = com.intellij.util.ui.UIUtil.getInactiveTextColor()
            }
            return component
        }
    }

    private fun createToolbar(): ActionToolbar {
        val toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, toolbarGroup(), true)
        toolbar.targetComponent = this
        return toolbar
    }

    fun toolbarGroup(): DefaultActionGroup {
        val group = DefaultActionGroup()
        ActionManager.getInstance().getAction(TOOLBAR_GROUP_ID)?.let { group.add(it) }
        return group
    }

    private inner class RefreshStatusAction : DumbAwareAction(
        "새로 고침",
        "설정·Git 상태와 현재 스프린트를 Jira에서 다시 읽습니다",
        AllIcons.Actions.Refresh,
    ) {
        override fun actionPerformed(event: AnActionEvent) = refreshInBackground(force = true)
    }

    private inner class OpenSettingsAction : DumbAwareAction(
        "설정 열기",
        "Settings | Tools | Jira Task를 엽니다",
        AllIcons.Nodes.Plugin,
    ) {
        override fun actionPerformed(event: AnActionEvent) = openSettings()
    }

    companion object {
        const val TOOLBAR_PLACE: String = "JiraTaskToolWindow"
        const val TOOLBAR_GROUP_ID: String = "JiraTask.ToolWindowToolbar"
        const val RUN_WIZARD: String = "설정 마법사 실행"
        const val OPEN_SETTINGS: String = "설정 열기"
        const val TOOL_WINDOW_ID: String = "Jira Task"
        const val START_TASK: String = "선택한 작업 시작"
        const val REFRESH: String = "새로 고침"
        const val START_HINT: String = "아래에서 작업을 골라 시작하세요."
        const val SPRINT_LOADING: String = "현재 스프린트를 읽는 중입니다…"
        const val UNKNOWN_VALUE: String = "알 수 없음"
    }
}

class JiraTaskToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JiraTaskToolWindowPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
        toolWindow.setTitleActions(panel.titleActions())
        panel.refreshInBackground()
    }

    override fun shouldBeAvailable(project: Project): Boolean = project.basePath != null
}
