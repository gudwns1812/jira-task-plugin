package io.github.gudwns1812.jiratask.ui.issue

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.panel
import io.github.gudwns1812.jiratask.domain.policy.WorkPolicy
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent

/**
 * CLI의 `template` + `validate --file` + `create --dry-run`을 하나로 합친 다이얼로그다.
 * 입력이 바뀔 때마다 검증 결과를 갱신하고, "미리보기"로 생성 요청 초안(JSON)을 보여준다.
 *
 * 실제 Jira 생성은 CLI에도 없는 기능이라(`create`는 `--dry-run` 없이는 거부한다) 여기서도 제공하지 않는다.
 * 대신 확인 버튼은 만들어진 초안 JSON을 클립보드로 복사한다.
 */
class CreateIssueDraftDialog(
    project: Project?,
    private val projectKey: String,
    private val policy: WorkPolicy = WorkPolicy.DEFAULT,
) : DialogWrapper(project, false) {

    val form: IssueDraftForm = IssueDraftForm { refresh() }

    private val problemsArea: JBTextArea = readOnlyArea(4)
    private val previewArea: JBTextArea = readOnlyArea(10)

    private var view: IssueDraftView = IssueDraftPresenter.present(form.values(), projectKey, policy)

    init {
        title = "Jira 이슈 초안"
        setOKButtonText("초안 복사")
        init()
        refresh()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { cell(form.panel) }
        group("검증 결과") {
            row { cell(JBScrollPane(problemsArea)) }
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
    fun problemsText(): String = problemsArea.text

    /** 화면에 보이는 요청 초안 문구. 미리보기를 누르기 전에는 빈 문자열이다. */
    fun previewText(): String = previewArea.text

    fun refresh() {
        view = IssueDraftPresenter.present(form.values(), projectKey, policy)
        problemsArea.text = problemsText(view)
        isOKActionEnabled = view.isValid
        if (!view.isValid) {
            previewArea.text = ""
        }
    }

    fun showPreview() {
        previewArea.text = view.json ?: NO_DRAFT
    }

    override fun doOKAction() {
        val json = view.json ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(json))
        super.doOKAction()
    }

    private fun problemsText(view: IssueDraftView): String = buildString {
        view.errors.forEach { appendLine("오류 - $it") }
        view.warnings.forEach { appendLine("경고 - $it") }
        if (view.errors.isEmpty()) {
            appendLine("오류 없음. 레이블: ${view.labels.joinToString(", ").ifEmpty { "없음" }}")
        }
    }.trimEnd()

    private fun readOnlyArea(rows: Int): JBTextArea = JBTextArea(rows, 60).apply {
        isEditable = false
        lineWrap = true
    }

    companion object {
        const val NO_DRAFT: String = "오류를 먼저 해결하세요."
    }
}
