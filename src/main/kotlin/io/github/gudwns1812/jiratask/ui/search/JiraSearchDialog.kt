package io.github.gudwns1812.jiratask.ui.search

import io.github.gudwns1812.jiratask.ui.applyHangulFallbackToWindow
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import io.github.gudwns1812.jiratask.domain.jira.JiraIssueDetail
import io.github.gudwns1812.jiratask.domain.jira.SprintTask
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

sealed interface SearchDetailState {
    data object None : SearchDetailState
    data class Editing(val edit: IssueEdit) : SearchDetailState
}

class JiraSearchDialog(
    project: Project?,
    private val projectKey: String?,
) : DialogWrapper(project, false) {

    private val model = DefaultListModel<SprintTask>()

    val searchField: JBTextField = JBTextField().apply {
        addActionListener { search() }
    }

    val resultList: JBList<SprintTask> = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        setCellRenderer { _, value, _, _, _ -> JBLabel(value?.label().orEmpty()) }
        addListSelectionListener { if (!it.valueIsAdjusting) onSelected(selectedValue) }
    }

    val summaryField: JBTextField = JBTextField()

    val descriptionArea: JBTextArea = JBTextArea(8, 60)

    private val statusLabel = JBLabel("")

    private val metaLabel = JBLabel("")

    private var state: SearchDetailState = SearchDetailState.None

    var onOpen: (String) -> Unit = {}

    var onSave: (IssueEdit) -> Unit = {}

    var onSearch: (JiraSearchQuery) -> Unit = {}

    init {
        title = "Jira Quick Search"
        setOKButtonText("저장")
        init()
        applyHangulFallbackToWindow()

        listOf(summaryField to { }, descriptionArea to { }).forEach { (field, _) ->
            (field as javax.swing.text.JTextComponent).document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(event: DocumentEvent) = refreshState()
            })
        }
        refreshState()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            cell(searchField).columns(COLUMNS_LARGE)
        }
        row { cell(JBScrollPane(resultList)).resizableColumn() }
        row { cell(statusLabel) }
        separator()
        row { cell(metaLabel) }
        row("제목:") { cell(summaryField).columns(COLUMNS_LARGE) }
        row("설명:") { cell(JBScrollPane(descriptionArea)).resizableColumn() }
            
    }

    override fun getPreferredFocusedComponent(): JComponent = searchField

    public override fun doOKAction() {
        (state as? SearchDetailState.Editing)?.let { onSave(currentEdit(it.edit)) }
    }

    fun search() = onSearch(JiraSearchQuery.of(searchField.text, projectKey))

    fun showResults(results: List<SprintTask>) {
        model.clear()
        results.forEach(model::addElement)
        statusLabel.text = if (results.isEmpty()) NO_RESULT else ""
        refreshState()
    }

    fun showProblem(message: String) {
        model.clear()
        statusLabel.text = message
        refreshState()
    }

    fun showDetail(detail: JiraIssueDetail) {
        state = SearchDetailState.Editing(IssueEdit(detail))
        summaryField.text = detail.summary
        descriptionArea.text = detail.description
        metaLabel.text = metaOf(detail)
        refreshState()
    }

    fun markSaved() {
        val editing = state as? SearchDetailState.Editing ?: return
        state = SearchDetailState.Editing(currentEdit(editing.edit).saved())
        refreshState()
    }

    fun statusText(): String = statusLabel.text.orEmpty()

    fun metaText(): String = metaLabel.text.orEmpty()

    fun editState(): SearchDetailState = state

    fun currentEdit(): IssueEdit? = (state as? SearchDetailState.Editing)?.let { currentEdit(it.edit) }

    private fun currentEdit(edit: IssueEdit): IssueEdit =
        edit.copy(summary = summaryField.text, description = descriptionArea.text)

    private fun onSelected(task: SprintTask?) {
        task?.let { onOpen(it.key) }
    }

    private fun refreshState() {
        val edit = currentEdit()
        isOKActionEnabled = edit?.canSave() == true
        val problem = edit?.takeIf { it.isModified() }?.problem()
        if (problem != null) {
            statusLabel.text = problem
        }
    }

    private fun metaOf(detail: JiraIssueDetail): String = listOfNotNull(
        detail.key,
        detail.statusName.ifBlank { null },
        detail.assignee.ifBlank { null },
        detail.sprint.ifBlank { null },
    ).joinToString("  ·  ")

    companion object {
        const val NO_RESULT: String = "결과가 없습니다."
    }
}
