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

data class StartTaskChoice(val taskNumber: String, val task: SprintTask?) {
    fun movesToInProgress(): Boolean = task == null || SprintTaskList.movesToInProgress(task)

    fun notice(): String? = task?.takeIf { it.statusCategory.isDone }?.let { SprintTaskList.DONE_NOTICE }
}

class StartTaskDialog(
    project: Project?,
    private val currentTaskNumber: String? = null,
) : DialogWrapper(project, false) {

    private val model = DefaultListModel<SprintTaskRow>()

    val taskList: JBList<SprintTaskRow> = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = RowRenderer()
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

    private var loaded: List<SprintTask> = emptyList()

    init {
        title = "Start Jira Task"
        setOKButtonText("작업 시작")
        init()
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

    fun statusText(): String = statusLabel.text.orEmpty()

    fun noticeText(): String = noticeLabel.text.orEmpty()

    fun choice(): StartTaskChoice {
        val number = taskNumberField.text.trim()
        val selected = selectedTask()?.takeIf { numberOf(it.key) == number }
            ?: loaded.firstOrNull { numberOf(it.key) == number }
        return StartTaskChoice(number, selected)
    }

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

    private fun numberOf(issueKey: String): String = issueKey.substringAfterLast('-')

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
