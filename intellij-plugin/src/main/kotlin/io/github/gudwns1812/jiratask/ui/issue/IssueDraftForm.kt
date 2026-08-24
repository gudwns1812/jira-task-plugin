package io.github.gudwns1812.jiratask.ui.issue

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/**
 * 이슈 입력 폼. 작업(task)은 손으로 쓸 것을 최소로 줄인 **간단 모드**가 기본이다 —
 * 구분(브랜치에서 알아낸 기능 ID 또는 업무 분류)과 영역을 고르고 제목 요약·설명·결과물만 쓴다.
 * 나머지(에픽/스토리 키, 담당자, 우선순위, 포인트, 스프린트, 기한)는 [IssueDraftContext]가 채워
 * 접힌 "고급" 영역에 두고, 필요할 때만 펼쳐 고친다.
 */
class IssueDraftForm(
    private val context: IssueDraftContext,
    private val onChange: () -> Unit = {},
) {
    val typeComboBox: ComboBox<String> =
        ComboBox(DefaultComboBoxModel(IssueDraftPresenter.TYPES.toTypedArray()))

    // 간단 모드
    val scopeComboBox: ComboBox<String> = ComboBox(DefaultComboBoxModel(context.scopeChoices().toTypedArray()))
    val areaComboBox: ComboBox<String> =
        ComboBox(DefaultComboBoxModel((listOf(NO_AREA) + context.policy.areas).toTypedArray()))
    val summaryField: JBTextField = JBTextField()
    val titleField: JBTextField = JBTextField()
    val descriptionArea: JBTextArea = JBTextArea(3, 40)
    val deliverablesArea: JBTextArea = JBTextArea(4, 40)
    val titlePreviewLabel: JBLabel = JBLabel()

    // 고급
    val epicKeyField: JBTextField = JBTextField(context.epicKey.orEmpty())
    val storyKeyField: JBTextField = JBTextField(context.storyKey.orEmpty())
    val branchField: JBTextField = JBTextField(context.branch.orEmpty())
    val assigneeField: JBTextField = JBTextField(context.remembered.assignee.orEmpty())
    val priorityField: JBTextField = JBTextField(context.remembered.priority.orEmpty())
    val storyPointsComboBox: ComboBox<Int> =
        ComboBox(DefaultComboBoxModel(context.policy.storyPoints.toTypedArray()))
    val sprintField: JBTextField = JBTextField(context.remembered.sprint.orEmpty())
    val dueDateField: JBTextField = JBTextField(context.remembered.dueDate.orEmpty())
    val templateArea: JBTextArea = JBTextArea(3, 40).apply {
        isEditable = false
        isOpaque = false
    }

    private lateinit var scopeRow: Row
    private lateinit var areaRow: Row
    private lateinit var summaryRow: Row
    private lateinit var titleRow: Row
    private lateinit var titlePreviewRow: Row
    private lateinit var descriptionRow: Row
    private lateinit var deliverablesRow: Row
    private lateinit var branchRow: Row
    private lateinit var templateRow: Row

    val panel: JComponent = panel {
        row("이슈 유형:") { cell(typeComboBox) }

        scopeRow = row("구분:") { cell(scopeComboBox) }
            .rowComment("기능 ID는 현재 브랜치의 스토리에서 가져옵니다. 공통 업무는 업무 분류를 고르세요.")
        areaRow = row("영역:") { cell(areaComboBox) }
        summaryRow = row("제목 요약:") { cell(summaryField).columns(COLUMNS_LARGE) }
        titleRow = row("제목:") { cell(titleField).columns(COLUMNS_LARGE) }
        titlePreviewRow = row("만들어질 제목:") { cell(titlePreviewLabel) }

        descriptionRow = row("설명:") { cell(JBScrollPane(descriptionArea)) }
        deliverablesRow = row("결과물:") { cell(JBScrollPane(deliverablesArea)) }
            .rowComment("한 줄에 하나씩 입력합니다.")

        collapsibleGroup("고급", indent = false) {
            row("에픽 키:") { cell(epicKeyField).columns(COLUMNS_MEDIUM) }
            row("스토리 키:") { cell(storyKeyField).columns(COLUMNS_MEDIUM) }
            branchRow = row("브랜치:") { cell(branchField).columns(COLUMNS_MEDIUM) }
            row("담당자:") { cell(assigneeField).columns(COLUMNS_MEDIUM) }
            row("우선순위:") { cell(priorityField).columns(COLUMNS_MEDIUM) }
            row("스토리 포인트:") { cell(storyPointsComboBox) }
            row("스프린트:") { cell(sprintField).columns(COLUMNS_MEDIUM) }
            row("기한:") { cell(dueDateField).columns(COLUMNS_MEDIUM) }
                .rowComment("YYYY-MM-DD 형식입니다.")
        }

        templateRow = row("작성 예시:") { cell(JBScrollPane(templateArea)) }

        context.lookupNote?.let { note ->
            row { comment(note) }
        }
    }

    init {
        context.defaultScope()?.let { scopeComboBox.selectedItem = it }
        areaComboBox.selectedItem = context.defaultArea() ?: NO_AREA
        context.defaultStoryPoints()?.let { storyPointsComboBox.selectedItem = it }

        listOf(
            summaryField, titleField, epicKeyField, storyKeyField, branchField,
            assigneeField, priorityField, sprintField, dueDateField,
        ).forEach { field ->
            field.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(event: DocumentEvent) = changed()
            })
        }
        listOf(descriptionArea, deliverablesArea).forEach { area ->
            area.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(event: DocumentEvent) = changed()
            })
        }
        typeComboBox.addActionListener {
            applyType(selectedType())
            onChange()
        }
        listOf(scopeComboBox, areaComboBox, storyPointsComboBox).forEach { combo ->
            combo.addActionListener { changed() }
        }

        applyType(selectedType())
    }

    fun selectedType(): String = typeComboBox.selectedItem as? String ?: IssueDraftPresenter.TYPES.last()

    fun selectType(type: String) {
        typeComboBox.selectedItem = type
    }

    /** 작업 제목은 구분·영역·요약을 조립해 만든다. 다른 유형은 제목을 그대로 쓴다. */
    fun composedTitle(): String = when (selectedType()) {
        "task" -> TaskTitles.compose(scope(), area(), summaryField.text)
        else -> titleField.text
    }

    fun values(): IssueFormInput = IssueFormInput(
        type = selectedType(),
        title = composedTitle(),
        epicKey = epicKeyField.text,
        storyKey = storyKeyField.text,
        branch = branchField.text,
        sprint = sprintField.text,
        assignee = assigneeField.text,
        priority = priorityField.text,
        storyPoints = (storyPointsComboBox.selectedItem as? Int)?.toString().orEmpty(),
        dueDate = dueDateField.text,
        summary = descriptionArea.text,
        deliverables = deliverablesArea.text,
    )

    /** 다음에 열 때 다시 채우기 위해 기억할 값. */
    fun rememberedInput(): RememberedInput = RememberedInput(
        scope = scope(),
        area = area(),
        assignee = assigneeField.text,
        priority = priorityField.text,
        storyPoints = storyPointsComboBox.selectedItem as? Int,
        sprint = sprintField.text,
        dueDate = dueDateField.text,
        epicKey = epicKeyField.text,
    )

    private fun scope(): String = scopeComboBox.selectedItem as? String ?: ""

    private fun area(): String? = (areaComboBox.selectedItem as? String)?.takeUnless { it == NO_AREA }

    private fun changed() {
        updatePreview()
        onChange()
    }

    private fun updatePreview() {
        titlePreviewLabel.text = composedTitle()
    }

    private fun applyType(type: String) {
        val isTask = type == "task"
        scopeRow.visible(isTask)
        areaRow.visible(isTask)
        summaryRow.visible(isTask)
        titlePreviewRow.visible(isTask)
        descriptionRow.visible(isTask)
        deliverablesRow.visible(isTask)
        titleRow.visible(!isTask)
        branchRow.visible(type == "story")
        templateArea.text = IssueDraftPresenter.templateFor(type)
        templateRow.visible(templateArea.text.isNotEmpty())
        // 생성 중에도 불리므로 여기서는 onChange를 부르지 않는다 — 부르면 아직 만들어지지 않은
        // 다이얼로그의 필드를 건드려 NPE가 난다.
        updatePreview()
    }

    companion object {
        const val NO_AREA: String = "(없음)"
    }
}
