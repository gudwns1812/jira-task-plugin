package io.github.gudwns1812.jiratask.ui.setup

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.gudwns1812.jiratask.application.SetupChoice

/** 마스킹이 꺼진 상태를 뜻하는 JPasswordField의 기본값. */
private val NO_MASK = Character.MIN_VALUE

/** 다이얼로그를 띄우지 않고 폼의 검증·정규화·선택 매핑만 확인한다. */
class SetupFormsTest : BasePlatformTestCase() {

    private fun filledForm(
        baseUrl: String = "https://team.atlassian.net",
        email: String = "gudwns1423@gmail.com",
        apiToken: String = "token",
    ): SetupConnectionForm = SetupConnectionForm(baseUrl, email).apply { apiTokenField.text = apiToken }

    fun `test accepts a complete connection and normalizes it`() {
        val form = filledForm(baseUrl = "  https://team.atlassian.net/  ", email = "  gudwns1423@gmail.com  ")

        assertNull(form.problem())
        val connection = form.connection()
        assertEquals("https://team.atlassian.net", connection.baseUrl)
        assertEquals("gudwns1423@gmail.com", connection.email)
        assertEquals("token", connection.apiToken)
    }

    fun `test asks for every missing value in order`() {
        assertEquals("Jira 주소를 입력하세요.", filledForm(baseUrl = "   ").problem())
        assertEquals("Jira 이메일을 입력하세요.", filledForm(email = "").problem())
        assertEquals("Jira API Token을 입력하세요.", filledForm(apiToken = "").problem())
    }

    fun `test rejects an address that is not an http url`() {
        assertEquals("https://team.atlassian.net 형식으로 입력하세요.", filledForm(baseUrl = "team.atlassian.net").problem())
        assertEquals("https://team.atlassian.net 형식으로 입력하세요.", filledForm(baseUrl = "ftp://team.atlassian.net").problem())
    }

    fun `test masks the api token on screen but keeps it for the connection`() {
        val form = filledForm(apiToken = "super-secret")

        assertTrue("API 토큰 입력은 마스킹돼야 한다", form.apiTokenField.echoChar != NO_MASK)
        assertEquals("super-secret", form.connection().apiToken)
    }

    fun `test returns the jira id of the selected choice`() {
        val form = SetupChoiceForm(
            "프로젝트를 선택하세요.",
            listOf(SetupChoice("10000", "S15P11A108 - A108 프로젝트"), SetupChoice("10001", "OTHER - 다른 프로젝트")),
        )

        assertEquals("10000", form.selectedValue())

        form.comboBox.selectedIndex = 1

        assertEquals("10001", form.selectedValue())
    }

    fun `test has no selection when there is nothing to choose`() {
        val form = SetupChoiceForm("상태를 선택하세요.", emptyList())

        assertNull(form.selectedValue())
    }
}
