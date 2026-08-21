package io.github.gudwns1812.jiratask.ui.issue

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/**
 * 이슈 유형별 입력 폼. 유형을 바꾸면 그 유형에 해당하는 행만 보이고, 입력이 바뀌면 [onChange]로
 * 알려 실시간 검증을 돌게 한다. 설명 칸 아래에는 CLI `template <type>`과 같은 안내 문구를 보여준다.
 */
class IssueDraftForm(private val onChange: () -> Unit = {}) {

    val typeComboBox: ComboBox<String> =
        ComboBox(DefaultComboBoxModel(IssueDraftPresenter.TYPES.toTypedArray()))
    val titleField: JBTextField = JBTextField()
    val epicKeyField: JBTextField = JBTextField()
    val storyKeyField: JBTextField = JBTextField()
    val branchField: JBTextField = JBTextField()
    val sprintField: JBTextField = JBTextField()
    val assigneeField: JBTextField = JBTextField()
    val priorityField: JBTextField = JBTextField()
    val storyPointsField: JBTextField = JBTextField()
    val dueDateField: JBTextField = JBTextField()
    val summaryArea: JBTextArea = JBTextArea(3, 40)
    val deliverablesArea: JBTextArea = JBTextArea(4, 40)
    val templateArea: JBTextArea = JBTextArea(4, 40).apply {
        isEditable = false
        isOpaque = false
    }

    private lateinit var epicKeyRow: Row
    private lateinit var storyKeyRow: Row
    private lateinit var branchRow: Row
    private lateinit var sprintRow: Row
    private lateinit var assigneeRow: Row
    private lateinit var priorityRow: Row
    private lateinit var storyPointsRow: Row
    private lateinit var dueDateRow: Row
    private lateinit var summaryRow: Row
    private lateinit var deliverablesRow: Row

    val panel: JComponent = panel {
        row("이슈 유형:") { cell(typeComboBox) }
        row("제목:") { cell(titleField).columns(COLUMNS_LARGE) }
        epicKeyRow = row("에픽 키:") { cell(epicKeyField).columns(COLUMNS_LARGE) }
        storyKeyRow = row("스토리 키:") { cell(storyKeyField).columns(COLUMNS_LARGE) }
        branchRow = row("브랜치:") { cell(branchField).columns(COLUMNS_LARGE) }
        assigneeRow = row("담당자:") { cell(assigneeField).columns(COLUMNS_LARGE) }
        priorityRow = row("우선순위:") { cell(priorityField).columns(COLUMNS_LARGE) }
        storyPointsRow = row("스토리 포인트:") { cell(storyPointsField).columns(COLUMNS_LARGE) }
        sprintRow = row("스프린트:") { cell(sprintField).columns(COLUMNS_LARGE) }
        dueDateRow = row("기한:") { cell(dueDateField).columns(COLUMNS_LARGE) }
        summaryRow = row("설명:") { cell(JBScrollPane(summaryArea)) }
        deliverablesRow = row("결과물:") { cell(JBScrollPane(deliverablesArea)) }
            .rowComment("한 줄에 하나씩 입력합니다.")
        row("작성 예시:") { cell(JBScrollPane(templateArea)) }
    }

    init {
        listOf(
            titleField, epicKeyField, storyKeyField, branchField, sprintField,
            assigneeField, priorityField, storyPointsField, dueDateField,
        ).forEach { field ->
            field.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(event: DocumentEvent) = onChange()
            })
        }
        listOf(summaryArea, deliverablesArea).forEach { area ->
            area.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(event: DocumentEvent) = onChange()
            })
        }
        typeComboBox.addActionListener {
            applyType(selectedType())
            onChange()
        }
        applyType(selectedType())
    }

    fun selectedType(): String = typeComboBox.selectedItem as? String ?: IssueDraftPresenter.TYPES.last()

    fun selectType(type: String) {
        typeComboBox.selectedItem = type
    }

    fun values(): IssueFormInput = IssueFormInput(
        type = selectedType(),
        title = titleField.text,
        epicKey = epicKeyField.text,
        storyKey = storyKeyField.text,
        branch = branchField.text,
        sprint = sprintField.text,
        assignee = assigneeField.text,
        priority = priorityField.text,
        storyPoints = storyPointsField.text,
        dueDate = dueDateField.text,
        summary = summaryArea.text,
        deliverables = deliverablesArea.text,
    )

    private fun applyType(type: String) {
        epicKeyRow.visible(type != "epic")
        storyKeyRow.visible(type == "task")
        branchRow.visible(type == "story")
        sprintRow.visible(type != "epic")
        assigneeRow.visible(type == "task")
        priorityRow.visible(type == "task")
        storyPointsRow.visible(type == "task")
        dueDateRow.visible(type == "task")
        summaryRow.visible(type == "task")
        deliverablesRow.visible(type == "task")
        templateArea.text = IssueDraftPresenter.templateFor(type)
    }
}
