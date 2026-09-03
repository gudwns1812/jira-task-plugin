package io.github.gudwns1812.jiratask.ui.setup

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import io.github.gudwns1812.jiratask.application.SetupChoice
import io.github.gudwns1812.jiratask.application.SetupConnection
import io.github.gudwns1812.jiratask.application.of
import io.github.gudwns1812.jiratask.application.problemOf
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

/**
 * 다이얼로그와 분리한 연결 입력 폼. 다이얼로그 껍데기 없이도 검증 규칙을 테스트할 수 있게 나눴다.
 * API 토큰은 [JBPasswordField]로 받아 화면에 원문이 보이지 않는다.
 */
class SetupConnectionForm(initialBaseUrl: String = "", initialEmail: String = "") {

    val baseUrlField: JBTextField = JBTextField(initialBaseUrl)
    val emailField: JBTextField = JBTextField(initialEmail)
    val apiTokenField: JBPasswordField = JBPasswordField()

    val panel: JComponent = panel {
        row("Jira 주소:") { cell(baseUrlField).columns(COLUMNS_LARGE) }
        row("Jira 이메일:") { cell(emailField).columns(COLUMNS_LARGE) }
        row("Jira API Token:") { cell(apiTokenField).columns(COLUMNS_LARGE) }
            
    }

    fun problem(): String? = SetupConnection.problemOf(baseUrlField.text, emailField.text, apiToken())

    fun connection(): SetupConnection = SetupConnection.of(baseUrlField.text, emailField.text, apiToken())

    private fun apiToken(): String = String(apiTokenField.password)
}

/** 프로젝트·이슈 유형·상태를 고르는 콤보박스 폼. 라벨은 사람이 읽는 문구, 값은 Jira ID다. */
class SetupChoiceForm(message: String, choices: List<SetupChoice>) {

    val comboBox: ComboBox<SetupChoice> = ComboBox(DefaultComboBoxModel(choices.toTypedArray())).apply {
        renderer = SimpleListCellRenderer.create("") { it.label }
        if (choices.isNotEmpty()) {
            selectedIndex = 0
        }
    }

    val panel: JComponent = panel {
        row { label(message) }
        row { cell(comboBox).columns(COLUMNS_LARGE) }
    }

    fun selectedValue(): String? = (comboBox.selectedItem as? SetupChoice)?.value
}
