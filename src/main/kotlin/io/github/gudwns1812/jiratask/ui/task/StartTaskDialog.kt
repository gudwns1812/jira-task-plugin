package io.github.gudwns1812.jiratask.ui.task

import io.github.gudwns1812.jiratask.ui.applyHangulFallbackToWindow
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import io.github.gudwns1812.jiratask.domain.jira.EmptySprintReason
import io.github.gudwns1812.jiratask.domain.jira.SprintTask
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/**
 * 목록에서 고른 작업, 또는 직접 입력한 번호.
 *
 * 목록을 못 불러와도 번호를 직접 칠 수 있어야 한다 — Jira가 죽었다고 커밋을 못 하면 안 된다.
 */
data class StartTaskChoice(val taskNumber: String, val task: SprintTask?) {

    /** 고른 작업을 `진행 중`으로 옮길 것인가. 직접 입력한 번호는 무엇인지 모르므로 옮겨 본다. */
    fun movesToInProgress(): Boolean = task == null || SprintTaskList.movesToInProgress(task)

    /** 화면에 덧붙일 안내. 없으면 null. */
    fun notice(): String? = task?.takeIf { it.statusCategory.isDone }?.let { SprintTaskList.DONE_NOTICE }
}

/**
 * 현재 스프린트의 내 작업을 고르는 다이얼로그.
 *
 * 화면에는 세 가지 상태가 있다 — 읽는 중, 목록, 목록 없음. 마지막 경우에도 번호 입력 칸은 살아 있다.
 */
class StartTaskDialog(
    project: Project?,
    private val currentTaskNumber: String? = null,
) : DialogWrapper(project, false) {

    private val model = DefaultListModel<SprintTaskRow>()

    val taskList: JBList<SprintTaskRow> = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = RowRenderer()
        // 목록이 길어질 때를 위한 탈출구. 그냥 타이핑하면 IDE가 겹쳐 그리는 찾기 상자다.
        // 한 사람의 스프린트 작업은 보통 열 몇 건이라 별도 검색 칸을 두지 않는다.
        ListSpeedSearch.installOn(this) { row -> (row as? SprintTaskRow.Task)?.task?.label() }
        addListSelectionListener {
            selectedTask()?.let { taskNumberField.text = numberOf(it.key) }
            refreshState()
        }
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2 && selectedTask() != null) {
                    doOKAction()
                }
            }
        })
    }

    val taskNumberField: JBTextField = JBTextField(currentTaskNumber.orEmpty())

    private val statusLabel = JBLabel(LOADING)

    private val noticeLabel = JBLabel("")

    /** 마지막으로 받은 목록. 검색은 이 값을 걸러서 보여준다. */
    private var loaded: List<SprintTask> = emptyList()

    init {
        title = "Start Jira Task"
        setOKButtonText("작업 시작")
        init()
        // 테마 글꼴에 한글이 없으면 이 창의 글자가 통째로 네모가 된다.
        applyHangulFallbackToWindow()

        taskNumberField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) = refreshState()
        })
        refreshState()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { cell(JBScrollPane(taskList)).resizableColumn() }
        row { cell(statusLabel) }
        row("작업 번호:") { cell(taskNumberField).columns(COLUMNS_MEDIUM) }
            
        row { cell(noticeLabel) }
    }

    override fun getPreferredFocusedComponent(): JComponent = taskList

    public override fun doValidate(): ValidationInfo? = values().problem()?.let { ValidationInfo(it) }

    fun values(): TaskFormInput = TaskFormInput(taskNumberField.text)

    // 화면을 띄우지 않고도 상태 문구를 확인할 수 있게 공개 범위를 넓힌다.
    fun statusText(): String = statusLabel.text.orEmpty()

    fun noticeText(): String = noticeLabel.text.orEmpty()

    /** 지금 확정하면 무엇이 저장되는가. */
    fun choice(): StartTaskChoice {
        val number = taskNumberField.text.trim()
        val selected = selectedTask()?.takeIf { numberOf(it.key) == number }
            ?: loaded.firstOrNull { numberOf(it.key) == number }
        return StartTaskChoice(number, selected)
    }

    /**
     * 목록을 받았다. EDT에서 호출한다.
     * [emptyReason]은 목록이 비었을 때 왜 비었는지, [otherAssigneeCount]는 내 담당이 아니라 빠진 수다.
     */
    @JvmOverloads
    fun show(tasks: List<SprintTask>, emptyReason: EmptySprintReason? = null, otherAssigneeCount: Int = 0) {
        loaded = tasks
        showRows(tasks)
        statusLabel.text = if (tasks.isEmpty()) {
            (emptyReason ?: EmptySprintReason.UNKNOWN).message
        } else {
            SprintTaskList.excludedNotice(otherAssigneeCount)
        }
        selectCurrent()
        refreshState()
    }

    /** 목록을 받지 못했다. 번호 입력만 남는다. */
    fun showProblem(message: String) {
        loaded = emptyList()
        model.clear()
        statusLabel.text = message
        refreshState()
    }

    private fun showRows(tasks: List<SprintTask>) {
        model.clear()
        SprintTaskList.rowsOf(tasks).forEach(model::addElement)
    }

    private fun selectCurrent() {
        val number = currentTaskNumber ?: return
        val index = (0 until model.size()).firstOrNull { position ->
            (model.get(position) as? SprintTaskRow.Task)?.let { numberOf(it.task.key) == number } == true
        } ?: return
        taskList.selectedIndex = index
        taskList.ensureIndexIsVisible(index)
    }

    private fun selectedTask(): SprintTask? = (taskList.selectedValue as? SprintTaskRow.Task)?.task

    private fun refreshState() {
        isOKActionEnabled = values().problem() == null
        noticeLabel.text = choice().notice().orEmpty()
    }

    /** `S15P11A108-257` → `257`. 키에서 프로젝트 접두를 떼어 저장 형식으로 맞춘다. */
    private fun numberOf(issueKey: String): String = issueKey.substringAfterLast('-')

    /** 머리글은 굵게, 끝난 작업은 흐리게. 머리글은 고를 수 없다. */
    private inner class RowRenderer : DefaultListCellRenderer() {
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
                is SprintTaskRow.Task -> "    ${row.task.label()}"
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

    companion object {
        const val LOADING: String = "현재 스프린트의 작업을 읽는 중입니다…"
    }
}
