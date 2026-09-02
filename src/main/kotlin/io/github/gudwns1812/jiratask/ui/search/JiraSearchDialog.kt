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

/** 검색 결과를 하나 열었을 때의 화면 상태. */
sealed interface SearchDetailState {
    data object None : SearchDetailState
    data class Editing(val edit: IssueEdit) : SearchDetailState
}

/**
 * Jira 이슈를 찾아 제목·설명을 보고 고치는 창.
 *
 * **고칠 수 있는 것은 제목과 설명뿐이다.** 상태와 스프린트는 automation과 계획의 영역이고,
 * 삭제 버튼은 아예 만들지 않는다 — 확정된 스프린트에서는 누구도 이슈를 지우지 않는다(RULE.md 6.2).
 */
class JiraSearchDialog(
    project: Project?,
    private val projectKey: String?,
) : DialogWrapper(project, false) {

    private val model = DefaultListModel<SprintTask>()

    val searchField: JBTextField = JBTextField().apply {
        // Enter는 검색이다. 다이얼로그의 기본 동작(저장)을 가로챈다.
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

    /** 결과 하나를 열어 달라는 요청. 액션이 백그라운드에서 처리한다. */
    var onOpen: (String) -> Unit = {}

    /** 지금 편집 중인 것을 저장해 달라는 요청. */
    var onSave: (IssueEdit) -> Unit = {}

    /** 검색해 달라는 요청. */
    var onSearch: (JiraSearchQuery) -> Unit = {}

    init {
        title = "Jira Quick Search"
        setOKButtonText("저장")
        init()
        // 테마 글꼴에 한글이 없으면 이 창의 글자가 통째로 네모가 된다.
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
                .comment("이슈 키(PROJ-81), 번호(81), 또는 제목의 일부")
        }
        row { cell(JBScrollPane(resultList)).resizableColumn() }
        row { cell(statusLabel) }
        separator()
        row { cell(metaLabel) }
        row("제목:") { cell(summaryField).columns(COLUMNS_LARGE) }
        row("설명:") { cell(JBScrollPane(descriptionArea)).resizableColumn() }
            .rowComment("상태와 스프린트는 여기서 바꾸지 않습니다. Jira automation과 스프린트 계획이 정합니다.")
    }

    override fun getPreferredFocusedComponent(): JComponent = searchField

    // 창을 띄우지 않고도 저장 경로를 확인할 수 있게 공개 범위를 넓힌다.
    public override fun doOKAction() {
        (state as? SearchDetailState.Editing)?.let { onSave(currentEdit(it.edit)) }
    }

    /** 검색을 실행한다. 액션이 결과를 [showResults]로 돌려준다. */
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

    /** 이슈 하나를 열었다. */
    fun showDetail(detail: JiraIssueDetail) {
        state = SearchDetailState.Editing(IssueEdit(detail))
        summaryField.text = detail.summary
        descriptionArea.text = detail.description
        metaLabel.text = metaOf(detail)
        refreshState()
    }

    /** 저장이 끝났다. 지금 화면의 값이 새 원본이 된다. */
    fun markSaved() {
        val editing = state as? SearchDetailState.Editing ?: return
        state = SearchDetailState.Editing(currentEdit(editing.edit).saved())
        refreshState()
    }

    fun statusText(): String = statusLabel.text.orEmpty()

    fun metaText(): String = metaLabel.text.orEmpty()

    fun editState(): SearchDetailState = state

    /** 지금 저장하면 무엇이 보내지는가. */
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
