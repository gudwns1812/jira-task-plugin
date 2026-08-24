package io.github.gudwns1812.jiratask.ui.issue

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
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
 * 이슈 입력 폼. 손으로 쓸 것을 최소로 줄이는 것이 목적이다.
 *
 * - 작업(task) 제목은 구분(브랜치에서 알아낸 기능 ID 또는 업무 분류)·영역을 고르고 요약만 쓰면 조립된다.
 * - 담당자·우선순위·스프린트·기한처럼 스프린트 내내 그대로인 값은 [IssueDraftContext]가 채운다.
 * - **채워진 칸은 접어두고 아직 비어 있는 필수 칸은 항상 보여준다.** 접는 기준은 "이미 값이 있다"이지
 *   "고급 설정이다"가 아니다 — 필수인데 숨어 있어 못 찾는 일이 없어야 하니까. `모두 보기`로 전부 펼친다.
 * - 작성 예시는 별도 칸 대신 각 입력의 자리 문구(placeholder)로 보여준다.
 */
class IssueDraftForm(
    private val context: IssueDraftContext,
    private val onChange: () -> Unit = {},
) {
    val typeComboBox: ComboBox<String> =
        ComboBox(DefaultComboBoxModel(IssueDraftPresenter.TYPES.toTypedArray()))

    val scopeComboBox: ComboBox<String> = ComboBox(DefaultComboBoxModel(context.scopeChoices().toTypedArray()))
    val areaComboBox: ComboBox<String> =
        ComboBox(DefaultComboBoxModel((listOf(NO_AREA) + context.policy.areas).toTypedArray()))
    val summaryField: JBTextField = JBTextField()
    val titleField: JBTextField = JBTextField()
    val titlePreviewLabel: JBLabel = JBLabel()
    val descriptionArea: JBTextArea = JBTextArea(3, 40)
    val deliverablesArea: JBTextArea = JBTextArea(3, 40)

    val epicKeyField: JBTextField = JBTextField(context.epicKey.orEmpty())
    val storyKeyField: JBTextField = JBTextField(context.storyKey.orEmpty())
    val branchField: JBTextField = JBTextField(context.branch.orEmpty())
    val assigneeField: JBTextField = JBTextField(context.remembered.assignee.orEmpty())
    val priorityField: JBTextField = JBTextField(context.remembered.priority.orEmpty())
    val storyPointsComboBox: ComboBox<Int> =
        ComboBox(DefaultComboBoxModel(context.policy.storyPoints.toTypedArray()))
    val sprintField: JBTextField = JBTextField(context.remembered.sprint.orEmpty())
    val dueDateField: JBTextField = JBTextField(context.remembered.dueDate.orEmpty())

    /** 이미 채워진 칸까지 모두 펼쳐 보고 싶을 때. */
    val showAllCheckBox: JBCheckBox = JBCheckBox("채워진 값도 모두 보기")

    private lateinit var scopeRow: Row
    private lateinit var areaRow: Row
    private lateinit var summaryRow: Row
    private lateinit var titleRow: Row
    private lateinit var titlePreviewRow: Row
    private lateinit var descriptionRow: Row
    private lateinit var deliverablesRow: Row
    private lateinit var rowsByPath: Map<String, Row>

    /** Row는 가시성을 되읽을 수 없으므로(setter만 있다) 지금 보이는 칸을 따로 기억한다. */
    private val shownPaths = mutableSetOf<String>()

    val panel: JComponent = panel {
        row("이슈 유형:") { cell(typeComboBox) }

        scopeRow = row("구분:") { cell(scopeComboBox) }
        areaRow = row("영역:") { cell(areaComboBox) }
        summaryRow = row("제목 요약:") { cell(summaryField).columns(COLUMNS_LARGE) }
        titleRow = row("제목:") { cell(titleField).columns(COLUMNS_LARGE) }
        titlePreviewRow = row("만들어질 제목:") { cell(titlePreviewLabel) }
        descriptionRow = row("설명:") { cell(JBScrollPane(descriptionArea)) }
        deliverablesRow = row("결과물:") { cell(JBScrollPane(deliverablesArea)) }
            .rowComment("한 줄에 하나씩 입력합니다.")

        val epicKeyRow = row("에픽 키:") { cell(epicKeyField).columns(COLUMNS_MEDIUM) }
        val storyKeyRow = row("스토리 키:") { cell(storyKeyField).columns(COLUMNS_MEDIUM) }
        val branchRow = row("브랜치:") { cell(branchField).columns(COLUMNS_MEDIUM) }
        val assigneeRow = row("담당자:") { cell(assigneeField).columns(COLUMNS_MEDIUM) }
        val priorityRow = row("우선순위:") { cell(priorityField).columns(COLUMNS_MEDIUM) }
        val storyPointsRow = row("스토리 포인트:") { cell(storyPointsComboBox) }
        val sprintRow = row("스프린트:") { cell(sprintField).columns(COLUMNS_MEDIUM) }
        val dueDateRow = row("기한:") { cell(dueDateField).columns(COLUMNS_MEDIUM) }

        rowsByPath = mapOf(
            "epicKey" to epicKeyRow,
            "storyKey" to storyKeyRow,
            "branch" to branchRow,
            "assignee" to assigneeRow,
            "priority" to priorityRow,
            "storyPoints" to storyPointsRow,
            "sprint" to sprintRow,
            "dueDate" to dueDateRow,
        )

        row { cell(showAllCheckBox) }

        context.lookupNote?.let { note -> row { comment(note) } }
    }

    init {
        // 대부분 작업(task)을 만든다 — 처음 열었을 때 바로 그 폼이 보이게 한다.
        typeComboBox.selectedItem = DEFAULT_TYPE
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
            applyLayout()
            onChange()
        }
        listOf(scopeComboBox, areaComboBox, storyPointsComboBox).forEach { combo ->
            combo.addActionListener { changed() }
        }
        showAllCheckBox.addActionListener {
            applyLayout()
            onChange()
        }

        applyLayout()
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

    /** 검증에서 문제가 나온 칸은 접혀 있어도 펼친다 — 고칠 수 없는 오류를 보여주지 않기 위해. */
    fun revealProblems(paths: Set<String>) {
        paths.forEach { path -> if (rowsByPath.containsKey(path)) setRowVisible(path, true) }
    }

    /** 지금 화면에 보이는 입력 칸의 필드 경로. */
    fun visiblePaths(): Set<String> = shownPaths.toSet()

    private fun scope(): String = scopeComboBox.selectedItem as? String ?: ""

    private fun area(): String? = (areaComboBox.selectedItem as? String)?.takeUnless { it == NO_AREA }

    private fun changed() {
        updatePreview()
        onChange()
    }

    private fun updatePreview() {
        titlePreviewLabel.text = composedTitle()
    }

    /**
     * 유형과 "모두 보기"에 따라 어떤 칸을 보여줄지 정한다. 값이 이미 있으면 접고, 비어 있는 칸은 보여준다.
     * 타이핑 중에는 다시 계산하지 않는다 — 값을 입력하는 순간 그 줄이 사라지면 안 되니까.
     */
    private fun applyLayout() {
        val type = selectedType()
        val isTask = type == "task"

        scopeRow.visible(isTask)
        areaRow.visible(isTask)
        summaryRow.visible(isTask)
        titlePreviewRow.visible(isTask)
        descriptionRow.visible(isTask)
        deliverablesRow.visible(isTask)
        titleRow.visible(!isTask)

        val required = requiredPathsOf(type)
        val optional = optionalPathsOf(type)
        rowsByPath.keys.forEach { path ->
            val belongsToType = path in required || path in optional
            val filled = valueOf(path).isNotBlank()
            setRowVisible(path, belongsToType && (showAllCheckBox.isSelected || !filled))
        }

        applyPlaceholders()
        updatePreview()
    }

    private fun setRowVisible(path: String, visible: Boolean) {
        rowsByPath.getValue(path).visible(visible)
        if (visible) {
            shownPaths += path
        } else {
            shownPaths -= path
        }
    }

    private fun valueOf(path: String): String = when (path) {
        "epicKey" -> epicKeyField.text
        "storyKey" -> storyKeyField.text
        "branch" -> branchField.text
        "assignee" -> assigneeField.text
        "priority" -> priorityField.text
        "storyPoints" -> (storyPointsComboBox.selectedItem as? Int)?.toString().orEmpty()
        "sprint" -> sprintField.text
        "dueDate" -> dueDateField.text
        else -> ""
    }

    /** 작성 예시를 별도 칸으로 두지 않고 각 입력의 자리 문구로 보여준다. */
    private fun applyPlaceholders() {
        summaryField.emptyText.text = "회원가입 폼"
        titleField.emptyText.text = if (selectedType() == "epic") "[AUTH] 인증" else "[AUTH-001] 이메일 회원가입"
        descriptionArea.emptyText.text = "작업 설명을 작성합니다."
        deliverablesArea.emptyText.text = "완료 기준 결과물 1"
        epicKeyField.emptyText.text = "${context.projectKey}-1"
        storyKeyField.emptyText.text = "${context.projectKey}-100"
        branchField.emptyText.text = "feat/100-signup"
        assigneeField.emptyText.text = "담당자 이름"
        priorityField.emptyText.text = "High"
        sprintField.emptyText.text = "Sprint 1"
        dueDateField.emptyText.text = "2026-08-31"
    }

    companion object {
        const val NO_AREA: String = "(없음)"
        const val DEFAULT_TYPE: String = "task"

        /** 비어 있으면 검증이 막히는 칸 — 절대 접어두지 않는다. */
        fun requiredPathsOf(type: String): Set<String> = when (type) {
            "task" -> setOf("epicKey", "assignee", "priority", "storyPoints", "sprint", "dueDate")
            "story" -> setOf("epicKey", "branch")
            else -> emptySet()
        }

        /** 있으면 좋지만 없어도 되는 칸. */
        fun optionalPathsOf(type: String): Set<String> = when (type) {
            "task" -> setOf("storyKey")
            "story" -> setOf("sprint")
            else -> emptySet()
        }
    }
}
