package io.github.gudwns1812.jiratask.ui

import io.github.gudwns1812.jiratask.application.SettingsSnapshot
import io.github.gudwns1812.jiratask.application.empty
import io.github.gudwns1812.jiratask.infrastructure.JiraTaskConfigStorage
import io.github.gudwns1812.jiratask.infrastructure.PolicyConfigRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 액션을 쓸 수 있는지 판단하는 규칙. 툴바 툴팁에 그대로 보이는 문구까지 확인한다. */
class JiraTaskAvailabilityTest {

    private val availability = JiraTaskAvailability(PolicyConfigRepository(JiraTaskConfigStorage()))

    private fun fullyConfigured() = SettingsSnapshot.empty().copy(
        projectKey = "S15P11A108",
        baseUrl = "https://team.atlassian.net",
        storyIssueType = "스토리",
    )

    @Test
    fun `disables everything without an open project`() {
        JiraTaskCommand.entries.forEach { command ->
            val result = availability.of(command, hasProject = false)

            assertFalse("$command 은 프로젝트 없이 쓸 수 없다", result.isEnabled)
            assertEquals(JiraTaskAvailability.NO_PROJECT, result.reason)
        }
    }

    @Test
    fun `enables everything when the setup is complete`() {
        JiraTaskCommand.entries.forEach { command ->
            val result = availability.of(command, fullyConfigured())

            assertTrue("$command 은 설정이 끝나면 쓸 수 있다", result.isEnabled)
            assertNull(result.reason)
        }
    }

    @Test
    fun `allows starting a task even without any jira configuration`() {
        val result = availability.of(JiraTaskCommand.START_TASK, SettingsSnapshot.empty())

        assertTrue("작업 번호 저장은 Jira 설정 없이도 되는 git 전용 동작이다", result.isEnabled)
    }

    @Test
    fun `asks for a project key before a commit`() {
        val result = availability.of(JiraTaskCommand.COMMIT, SettingsSnapshot.empty())

        assertFalse(result.isEnabled)
        assertEquals(JiraTaskAvailability.NO_PROJECT_KEY, result.reason)
    }

    @Test
    fun `does not need an api token to enable the commit action`() {
        // 토큰은 OS 키체인에 있어 update()에서 읽지 않는다 — 스냅샷에 토큰이 없어도 활성이어야 한다.
        val result = availability.of(JiraTaskCommand.COMMIT, fullyConfigured())

        assertTrue(result.isEnabled)
    }

    @Test
    fun `explains a broken configuration instead of enabling the action`() {
        val storage = JiraTaskConfigStorage().apply { configJson = "{\"projectKey\": \"lowercase\"}" }
        val broken = JiraTaskAvailability(PolicyConfigRepository(storage))

        val result = broken.of(JiraTaskCommand.COMMIT, hasProject = true)

        assertFalse(result.isEnabled)
        assertTrue(result.reason!!.contains("projectKey"))
    }

    @Test
    fun `reads the saved configuration when only the project flag is given`() {
        val repository = PolicyConfigRepository(JiraTaskConfigStorage())
        val configured = JiraTaskAvailability(repository)
        assertFalse(configured.of(JiraTaskCommand.COMMIT, hasProject = true).isEnabled)

        repository.setProjectKey("S15P11A108")

        assertTrue(configured.of(JiraTaskCommand.COMMIT, hasProject = true).isEnabled)
    }
}
