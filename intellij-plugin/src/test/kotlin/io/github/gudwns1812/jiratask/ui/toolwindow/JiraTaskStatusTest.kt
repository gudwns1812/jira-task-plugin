package io.github.gudwns1812.jiratask.ui.toolwindow

import io.github.gudwns1812.jiratask.application.SettingsLoad
import io.github.gudwns1812.jiratask.application.SettingsSnapshot
import io.github.gudwns1812.jiratask.application.empty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 툴 윈도우가 그릴 상태를 만드는 규칙. */
class JiraTaskStatusTest {

    private fun configuredSnapshot() = SettingsSnapshot(
        projectKey = "S15P11A108",
        baseUrl = "https://team.atlassian.net",
        projectName = "A108 프로젝트",
        storyIssueType = "스토리",
        taskIssueType = "작업",
        epicIssueType = "에픽",
        storyStartStatus = "진행 중",
        taskDoneStatus = "완료",
        areas = listOf("FE"),
        workCategories = listOf("PLAN"),
        storyPoints = listOf(1),
    )

    private fun rowsOf(status: JiraTaskStatus): Map<String, String> = status.rows().toMap()

    @Test
    fun `shows the saved configuration with the task number and the branch`() {
        val status = JiraTaskStatus.of(SettingsLoad.Loaded(configuredSnapshot()), "257", "feat/100-signup")

        assertTrue(status.isConfigured)
        assertNull(status.problem)
        val rows = rowsOf(status)
        assertEquals("S15P11A108", rows["프로젝트 키"])
        assertEquals("https://team.atlassian.net", rows["Jira 주소"])
        assertEquals("A108 프로젝트", rows["프로젝트"])
        assertEquals("스토리", rows["스토리 유형"])
        assertEquals("진행 중", rows["스토리 시작 상태"])
        assertEquals("257", rows["현재 작업 번호"])
        assertEquals("feat/100-signup", rows["현재 브랜치"])
    }

    @Test
    fun `asks for setup when no project key is stored`() {
        val status = JiraTaskStatus.of(SettingsLoad.Loaded(SettingsSnapshot.empty()), null, "main")

        assertFalse(status.isConfigured)
        assertEquals(JiraTaskStatus.NEEDS_SETUP, status.problem)
        assertEquals(JiraTaskStatus.NOT_CONFIGURED, rowsOf(status)["프로젝트 키"])
    }

    @Test
    fun `explains a broken config and keeps the git information`() {
        val status = JiraTaskStatus.of(SettingsLoad.Broken("설정 형식이 올바르지 않습니다."), "257", "feat/100-signup")

        assertFalse(status.isConfigured)
        assertEquals("설정 형식이 올바르지 않습니다.", status.problem)
        assertEquals("257", rowsOf(status)["현재 작업 번호"])
        assertEquals("feat/100-signup", rowsOf(status)["현재 브랜치"])
        assertEquals(JiraTaskStatus.NOT_CONFIGURED, rowsOf(status)["Jira 주소"])
    }

    @Test
    fun `says what is missing instead of leaving a row empty`() {
        val status = JiraTaskStatus.of(SettingsLoad.Loaded(configuredSnapshot()), null, null)

        val rows = rowsOf(status)
        assertEquals(JiraTaskStatus.NO_TASK, rows["현재 작업 번호"])
        assertEquals(JiraTaskStatus.NO_BRANCH, rows["현재 브랜치"])
    }

    @Test
    fun `never shows the email or the api token`() {
        val status = JiraTaskStatus.of(SettingsLoad.Loaded(configuredSnapshot()), "257", "feat/100-signup")

        assertTrue(status.rows().none { (_, value) -> value.contains("@") })
    }

    @Test
    fun `draws every row it declares`() {
        assertEquals(JiraTaskStatus.ROW_NAMES, JiraTaskStatus.EMPTY.rows().map { it.first })
        assertEquals(7, JiraTaskStatus.ROW_NAMES.size)
    }
}
