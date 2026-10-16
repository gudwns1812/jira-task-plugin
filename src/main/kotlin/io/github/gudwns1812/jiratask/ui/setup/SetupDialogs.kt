package io.github.gudwns1812.jiratask.ui.setup

import io.github.gudwns1812.jiratask.ui.applyHangulFallbackToWindow
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.builder.panel
import io.github.gudwns1812.jiratask.application.SetupCancelledError
import io.github.gudwns1812.jiratask.application.SetupChoice
import io.github.gudwns1812.jiratask.application.SetupConnection
import io.github.gudwns1812.jiratask.application.SetupPrompter
import io.github.gudwns1812.jiratask.application.SetupSummary
import io.github.gudwns1812.jiratask.application.toDisplayLines
import javax.swing.JComponent

class SetupConnectionDialog(
    project: Project?,
    initialBaseUrl: String = "",
    initialEmail: String = "",
) : DialogWrapper(project, false) {

    val form: SetupConnectionForm = SetupConnectionForm(initialBaseUrl, initialEmail)

    init {
        title = "Jira 연결 정보"
        init()
        applyHangulFallbackToWindow()
    }

    override fun createCenterPanel(): JComponent = form.panel

    override fun getPreferredFocusedComponent(): JComponent = form.baseUrlField

    override fun doValidate(): ValidationInfo? = form.problem()?.let { ValidationInfo(it) }
}

class SetupChoiceDialog(
    project: Project?,
    message: String,
    choices: List<SetupChoice>,
) : DialogWrapper(project, false) {

    val form: SetupChoiceForm = SetupChoiceForm(message, choices)

    init {
        title = "Jira Task 초기 설정"
        init()
        applyHangulFallbackToWindow()
    }

    override fun createCenterPanel(): JComponent = form.panel

    override fun getPreferredFocusedComponent(): JComponent = form.comboBox
}

class SetupConfirmDialog(project: Project?, private val summary: SetupSummary) : DialogWrapper(project, false) {

    init {
        title = "설정 내용 확인"
        setOKButtonText("저장")
        init()
        applyHangulFallbackToWindow()
    }

    override fun createCenterPanel(): JComponent = panel {
        summary.toDisplayLines().forEach { (name, value) ->
            row("$name:") { label(value) }
        }
    }
}

class DialogSetupPrompter(
    private val project: Project?,
    initialBaseUrl: String = "",
    initialEmail: String = "",
    private val onProgress: (String) -> Unit = {},
) : SetupPrompter {

    private var lastBaseUrl: String = initialBaseUrl
    private var lastEmail: String = initialEmail

    override fun askConnection(): SetupConnection = onEdt {
        val dialog = SetupConnectionDialog(project, lastBaseUrl, lastEmail)
        if (!dialog.showAndGet()) {
            throw SetupCancelledError()
        }
        dialog.form.connection().also {
            lastBaseUrl = it.baseUrl
            lastEmail = it.email
        }
    }

    override fun choose(message: String, choices: List<SetupChoice>): String = onEdt {
        val dialog = SetupChoiceDialog(project, message, choices)
        if (!dialog.showAndGet()) {
            throw SetupCancelledError()
        }
        dialog.form.selectedValue() ?: throw SetupCancelledError()
    }

    override fun confirm(summary: SetupSummary): Boolean = onEdt {
        SetupConfirmDialog(project, summary).showAndGet()
    }

    override fun showSuccess(message: String) = onProgress(message)

    override fun showError(message: String) = onEdt {
        Messages.showErrorDialog(project, message, "Jira Task 초기 설정")
    }
}

private fun <T> onEdt(block: () -> T): T {
    var result: Result<T>? = null
    ApplicationManager.getApplication().invokeAndWait { result = runCatching(block) }
    return result!!.getOrThrow()
}
