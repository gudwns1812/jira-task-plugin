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

/**
 * 오른쪽 사이드바의 **Jira Task** 툴 윈도우. 조작은 상단 아이콘 툴바(=액션)로 하고, 본문은
 * 저장된 설정·현재 작업 번호·현재 브랜치를 보여주는 읽기 화면이다.
 *
 * 본문은 상태에 따라 세 화면 중 하나다 — 정상이면 상태 행, 설정이 없으면 마법사를 안내하는 빈 상태,
 * 설정이 손상됐으면 오류와 설정 열기 안내. 처음 쓰는 사용자에게 "설정되지 않음" 일곱 줄을 보여주는
 * 대신 다음에 할 일을 준다.
 *
 * 툴바가 액션을 직접 실행하므로 각 액션의 `update()`가 활성/비활성과 툴팁을 결정한다.
 */
class JiraTaskToolWindowPanel @JvmOverloads constructor(
    private val project: Project,
    parentDisposable: Disposable,
    private val browse: (String) -> Unit = { BrowserUtil.browse(it) },
    private val launchWizard: () -> Unit = { SetupWizardLauncher.launch(project) },
    private val openSettings: () -> Unit = {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, JiraTaskConfigurable::class.java)
    },
    /** 화면에 그릴 것을 읽는 방법. 기본은 [readBoard]이고, 테스트에서 갈아 끼운다. */
    private val boardReader: (() -> JiraTaskBoard)? = null,
    /**
     * 스프린트를 읽는 방법. 인자는 "새로 고침을 눌렀는가"이고, true면 캐시를 건너뛴다.
     * 그 갈림이 새로 고침 버튼의 전부라 테스트가 여기를 잡는다.
     */
    private val readSprint: (Boolean) -> SprintTaskLoad = { force ->
        SprintTasks.getInstance(project).let { if (force) it.reload() else it.current() }
    },
    /** git에서 현재 작업 번호를 읽는 방법. 기본은 로컬 git 설정이고, 테스트에서 갈아 끼운다. */
    private val taskNumberFromGit: () -> String? = {
        project.basePath?.let { runCatching { GitRepository(File(it)).getCurrentTaskNumber() }.getOrNull() }
    },
) : SimpleToolWindowPanel(true, true) {

    /** 본문에 보이는 화면. */
    enum class Shown { STATUS, SETUP_NEEDED, PROBLEM }

    private val repository = PolicyConfigRepository()

    /** 항목의 종류(일반 문구 / 링크 / 고정폭)는 고정이므로 컴포넌트도 한 번만 만든다. */
    private val rowViews: Map<String, StatusRowView> = JiraTaskStatus.EMPTY.settingsRows()
        .associate { it.name to StatusRowView.of(it.kind, browse) }

    private val problemLabel = JBLabel()

    /** 현재 작업. 번호가 아니라 키·제목·상태로 보여 준다. */
    private val taskKeyLabel = ActionLink("") { openCurrentIssue() }

    private val taskSummaryLabel = JBLabel()

    private val taskStatusLabel = JBLabel()

    private val branchLabel = JBLabel()

    private val sprintModel = DefaultListModel<SprintTaskRow>()

    /** 현재 스프린트. 여기서 바로 작업을 바꿀 수 있다 — 다이얼로그를 열 이유가 없다. */
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
        row { cell(taskKeyLabel) }
        row { cell(taskSummaryLabel) }
        row { cell(taskStatusLabel); cell(branchLabel) }
    }

    private val sprintPanel: JComponent = panel {
        group("현재 스프린트") {
            row { cell(JBScrollPane(sprintList)).resizableColumn() }
            row { cell(sprintNoticeLabel) }
            row {
                link(START_TASK) { startSelected() }
                link(REFRESH) { refreshInBackground(force = true) }
            }.rowComment("작업을 두 번 클릭해도 됩니다. Jira에서 바꾼 내용은 새로 고침해야 보입니다.")
        }
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
        show(JiraTaskBoard.EMPTY)
        subscribe(parentDisposable)
    }

    /**
     * 스스로 갱신하는 경로 두 가지: 액션이 성공해 상태가 바뀌었다는 통지([JiraTaskStateListener]),
     * 그리고 툴 윈도우가 다시 보이는 순간. 후자는 다른 창에서 브랜치를 바꾸고 돌아온 경우를 잡아준다.
     */
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

    /** 이 툴 윈도우가 보일 때만 다시 읽는다. */
    fun onToolWindowShown(toolWindowId: String) {
        if (toolWindowId == TOOL_WINDOW_ID) {
            refreshInBackground()
        }
    }

    /** 제목 표시줄(gear 줄)에 놓을 액션들. */
    fun titleActions(): List<AnAction> = listOf(RefreshStatusAction(), OpenSettingsAction())

    /** 지금 그려진 상태. 테스트와 새로 고침 판단에 쓴다. */
    fun currentStatus(): JiraTaskStatus = board.status

    /** 지금 그려진 화면 전체. */
    fun currentBoard(): JiraTaskBoard = board

    /** 본문에 어떤 화면이 보이는지. */
    fun shown(): Shown = when {
        setupNeededPanel.isVisible -> Shown.SETUP_NEEDED
        problemPanel.isVisible -> Shown.PROBLEM
        else -> Shown.STATUS
    }

    /**
     * git과 Jira를 읽어야 하므로 백그라운드에서 만든 뒤 화면에 반영한다.
     *
     * [force]는 사용자가 새로 고침을 눌렀다는 뜻이다. 이때는 스프린트 캐시를 건너뛰고 Jira에 다시 묻는다 —
     * 캐시를 그대로 쓰면 "새로 고침해도 그대로"가 되어 버튼이 거짓말을 한다.
     */
    @JvmOverloads
    fun refreshInBackground(force: Boolean = false) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val next = boardReader?.invoke() ?: readBoard(force)
            ApplicationManager.getApplication().invokeLater({ show(next) }) { project.isDisposed }
        }
    }

    /**
     * 백그라운드에서 화면에 그릴 것을 전부 읽는다(테스트에서는 직접 호출한다).
     *
     * Jira 조회는 설정이 갖춰졌을 때만 한다. 설정이 없으면 어차피 빈 상태 화면을 보여 주므로
     * 네트워크를 칠 이유가 없다.
     */
    @JvmOverloads
    fun readBoard(force: Boolean = false): JiraTaskBoard {
        val load = EditSettingsUseCase(repository::loadConfig, repository::saveConfig).load()
        val git = project.basePath?.let { GitRepository(File(it)) }
        val state = CurrentTaskState.getInstance(project)
        // git에서 읽은 값은 캐시에도 남긴다. 커밋 훅처럼 캐시만 보는 쪽이 빈손으로 돌아가지 않게 한다.
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
        taskStatusLabel.text = next.currentTask?.statusName.orEmpty()
        branchLabel.text = BRANCH_PREFIX + (next.status.branch ?: JiraTaskStatus.NO_BRANCH)
    }

    private fun showSprint(sprint: SprintTaskLoad?) {
        sprintModel.clear()
        sprintNoticeLabel.text = when (sprint) {
            null -> SPRINT_LOADING
            is SprintTaskLoad.Loaded -> {
                SprintTaskList.rowsOf(sprint.tasks).forEach(sprintModel::addElement)
                if (sprint.tasks.isEmpty()) (sprint.reason ?: EmptySprintReason.UNKNOWN).message else ""
            }
            is SprintTaskLoad.NotConfigured -> sprint.message
            is SprintTaskLoad.Failed -> sprint.message
        }
    }

    /** 목록에서 고른 작업을 시작한다. 머리글을 고른 경우에는 아무 일도 하지 않는다. */
    fun startSelected() {
        val task = (sprintList.selectedValue as? SprintTaskRow.Task)?.task ?: return
        StartTaskAction.start(project, StartTaskChoice(task.key.substringAfterLast('-'), task))
    }

    private fun openCurrentIssue() {
        val issueKey = board.status.currentIssueKey() ?: return
        board.status.issueUrl(issueKey)?.let(browse)
    }

    /** 머리글은 굵게, 끝난 작업은 흐리게. 머리글은 고를 수 없다. */
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

    /** 툴바 구성. 새로 고침·설정 열기는 제목 표시줄에 있으므로 여기에는 실행 액션만 둔다. */
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
        AllIcons.General.Settings,
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
        const val BRANCH_PREFIX: String = "브랜치: "
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
