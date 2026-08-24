package io.github.gudwns1812.jiratask.ui.issue

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.datatransfer.StringSelection
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * CLI의 `template` + `validate --file` + `create --dry-run`을 하나로 합친 다이얼로그다.
 * 입력이 바뀔 때마다 검증 결과를 갱신하고, "미리보기"로 생성 요청 초안(JSON)을 보여준다.
 *
 * 검증 결과는 필드 경로(`dueDate`) 대신 화면의 칸 이름(`기한`)으로 보여주고 오류는 빨간색으로 칠한다.
 * 문제가 난 칸이 접혀 있으면 펼쳐 바로 고칠 수 있게 한다.
 *
 * 실제 Jira 생성은 CLI에도 없는 기능이라(`create`는 `--dry-run` 없이는 거부한다) 여기서도 제공하지 않는다.
 * 대신 확인 버튼은 만들어진 초안 JSON을 클립보드로 복사한다.
 */
class CreateIssueDraftDialog @JvmOverloads constructor(
    project: Project?,
    private val context: IssueDraftContext,
    private val onRemember: (RememberedInput) -> Unit = {},
) : DialogWrapper(project, false) {

    val form: IssueDraftForm = IssueDraftForm(context) { refresh() }

    private val problemsPanel: JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private val previewArea: JBTextArea = JBTextArea(10, 60).apply {
        isEditable = false
        lineWrap = true
    }

    private var view: IssueDraftView = IssueDraftPresenter.present(form.values(), context.projectKey, context.policy)

    init {
        title = "Jira 이슈 초안"
        setOKButtonText("초안 복사")
        init()
        refresh()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { cell(form.panel) }
        group("검증 결과") {
            row { cell(problemsPanel) }
        }
        group("생성 요청 초안") {
            row {
                button("미리보기") { showPreview() }
            }
            row { cell(JBScrollPane(previewArea)) }
        }
    }

    /** 현재 입력에 대한 검증 결과. 다이얼로그를 띄우지 않고도 상태를 확인할 수 있게 공개한다. */
    fun currentView(): IssueDraftView = view

    /** 화면에 보이는 검증 결과 문구. */
    fun problemsText(): String = problemLabels().joinToString("\n") { it.text }

    /** 검증 결과 줄들. 색까지 확인할 수 있게 컴포넌트로 돌려준다. */
    fun problemLabels(): List<JBLabel> = problemsPanel.components.filterIsInstance<JBLabel>()

    /** 화면에 보이는 요청 초안 문구. 미리보기를 누르기 전에는 빈 문자열이다. */
    fun previewText(): String = previewArea.text

    fun refresh() {
        view = IssueDraftPresenter.present(form.values(), context.projectKey, context.policy)
        // 고칠 칸이 접혀 있으면 오류를 보여줘도 손을 쓸 수 없다.
        form.revealProblems(view.problemPaths())
        showProblems(view)
        isOKActionEnabled = view.isValid
        if (!view.isValid) {
            previewArea.text = ""
        }
    }

    fun showPreview() {
        previewArea.text = view.json ?: NO_DRAFT
    }

    // 다이얼로그를 띄우지 않고 확인 동작(복사 + 기억)을 검증할 수 있게 공개 범위를 넓힌다.
    public override fun doOKAction() {
        val json = view.json ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(json))
        // 다음에 열 때 다시 타이핑하지 않도록 이번에 쓴 값을 기억한다.
        onRemember(form.rememberedInput())
        super.doOKAction()
    }

    private fun showProblems(view: IssueDraftView) {
        problemsPanel.removeAll()

        view.errors.forEach { problem ->
            problemsPanel.add(problemLabel(problem.display(), AllIcons.General.Error, NamedColorUtil.getErrorForeground()))
        }
        view.warnings.forEach { problem ->
            problemsPanel.add(problemLabel(problem.display(), AllIcons.General.Warning, UIUtil.getLabelForeground()))
        }
        if (view.errors.isEmpty()) {
            val labels = view.labels.joinToString(", ").ifEmpty { "없음" }
            problemsPanel.add(
                problemLabel(
                    "입력에 문제가 없습니다. 레이블: $labels",
                    AllIcons.General.InspectionsOK,
                    UIUtil.getLabelForeground(),
                ),
            )
        }

        problemsPanel.revalidate()
        problemsPanel.repaint()
    }

    private fun problemLabel(text: String, icon: Icon, color: Color): JBLabel =
        JBLabel(text, icon, JBLabel.LEFT).apply {
            foreground = color
            border = JBUI.Borders.emptyBottom(2)
        }

    companion object {
        const val NO_DRAFT: String = "오류를 먼저 해결하세요."
    }
}
