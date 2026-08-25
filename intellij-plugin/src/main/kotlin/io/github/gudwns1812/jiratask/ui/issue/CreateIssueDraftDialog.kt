package io.github.gudwns1812.jiratask.ui.issue

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import java.awt.Color
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 이슈를 만드는 다이얼로그. CLI의 `template`(작성 예시) + `validate`(실시간 검증)에 더해,
 * 확인을 누르면 **실제로 Jira 이슈를 만든다**.
 *
 * 입력이 바뀔 때마다 검증하고, 문제는 필드 경로(`dueDate`) 대신 화면의 칸 이름(`기한`)으로
 * 빨간색으로 알려준다. 문제가 난 칸이 접혀 있으면 펼쳐 바로 고칠 수 있게 한다.
 */
class CreateIssueDraftDialog @JvmOverloads constructor(
    project: Project?,
    private val context: IssueDraftContext,
    private val onRemember: (RememberedInput) -> Unit = {},
    /** 확인을 눌렀을 때 실제 생성을 맡는다. 다이얼로그는 입력과 검증만 책임진다. */
    private val onCreate: (IssueFormInput, IssueDraftView) -> Unit = { _, _ -> },
) : DialogWrapper(project, false) {

    val form: IssueDraftForm = IssueDraftForm(context) { refresh() }

    private val problemsPanel: JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    private var view: IssueDraftView = IssueDraftPresenter.present(form.values(), context.projectKey, context.policy)

    init {
        title = "Jira 이슈 만들기"
        setOKButtonText("만들기")
        init()
        refresh()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { cell(form.panel) }
        group("검증 결과") {
            row { cell(problemsPanel) }
        }
    }

    /** 현재 입력에 대한 검증 결과. 다이얼로그를 띄우지 않고도 상태를 확인할 수 있게 공개한다. */
    fun currentView(): IssueDraftView = view

    /** 화면에 보이는 검증 결과 문구. */
    fun problemsText(): String = problemLabels().joinToString("\n") { it.text }

    /** 검증 결과 줄들. 색까지 확인할 수 있게 컴포넌트로 돌려준다. */
    fun problemLabels(): List<JBLabel> = problemsPanel.components.filterIsInstance<JBLabel>()

    fun refresh() {
        view = IssueDraftPresenter.present(form.values(), context.projectKey, context.policy)
        // 고칠 칸이 접혀 있으면 오류를 보여줘도 손을 쓸 수 없다.
        form.revealProblems(view.problemPaths())
        showProblems(view)
        isOKActionEnabled = view.isValid
    }

    // 다이얼로그를 띄우지 않고 확인 동작을 검증할 수 있게 공개 범위를 넓힌다.
    public override fun doOKAction() {
        if (!view.isValid) {
            return
        }
        // 다음에 열 때 다시 타이핑하지 않도록 이번에 쓴 값을 기억한다.
        onRemember(form.rememberedInput())
        onCreate(form.values(), view)
        super.doOKAction()
    }

    private fun showProblems(view: IssueDraftView) {
        problemsPanel.removeAll()

        view.errors.forEach { problem ->
            problemsPanel.add(
                problemLabel(problem.display(), AllIcons.General.Error, NamedColorUtil.getErrorForeground()),
            )
        }
        view.warnings.forEach { problem ->
            problemsPanel.add(problemLabel(problem.display(), AllIcons.General.Warning, UIUtil.getLabelForeground()))
        }
        if (view.errors.isEmpty()) {
            val labels = view.labels.joinToString(", ").ifEmpty { "없음" }
            problemsPanel.add(
                problemLabel(
                    "만들 준비가 됐습니다. 레이블: $labels",
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
}
