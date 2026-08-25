package io.github.gudwns1812.jiratask.infrastructure

import io.github.gudwns1812.jiratask.domain.jira.JiraNamedValue
import io.github.gudwns1812.jiratask.domain.policy.PolicyConfigurationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyConfigRepositoryTest {

    @Test
    fun `has no config initially and returns default policy`() {
        val repository = PolicyConfigRepository(JiraTaskConfigStorage())

        assertFalse(repository.hasConfig())
        assertEquals(listOf(1, 2, 3, 5, 8), repository.loadWorkPolicy().storyPoints)
    }

    @Test
    fun `saves and reloads a project key while preserving default policy`() {
        val repository = PolicyConfigRepository(JiraTaskConfigStorage())

        repository.setProjectKey("S15P11A108")
        val loaded = repository.loadConfig()

        assertTrue(repository.hasConfig())
        assertEquals("S15P11A108", loaded.projectKey)
        assertEquals(listOf(1, 2, 3, 5, 8), loaded.policy.storyPoints)
    }

    @Test
    fun `saves jira settings partially without losing previously saved fields`() {
        val repository = PolicyConfigRepository(JiraTaskConfigStorage())

        repository.setProjectKey("S15P11A108")
        repository.saveConfig(mapOf("jira" to mapOf("baseUrl" to "http://127.0.0.1:8080")))
        repository.saveConfig(
            mapOf(
                "jira" to mapOf(
                    "issueTypes" to mapOf("story" to mapOf("id" to "10001", "name" to "스토리")),
                    "statuses" to mapOf("story" to mapOf("start" to mapOf("id" to "21", "name" to "진행 중"))),
                ),
            ),
        )

        val loaded = repository.loadConfig()

        assertEquals("S15P11A108", loaded.projectKey)
        assertEquals("http://127.0.0.1:8080", loaded.jira?.baseUrl)
        assertEquals(JiraNamedValue("10001", "스토리"), loaded.jira?.issueTypes?.story)
        assertEquals(JiraNamedValue("21", "진행 중"), loaded.jira?.statuses?.story?.start)
    }

    @Test
    fun `rejects loading a corrupted config`() {
        val storage = JiraTaskConfigStorage().apply { configJson = "not-json" }
        val repository = PolicyConfigRepository(storage)

        assertThrows(PolicyConfigurationError::class.java) { repository.loadConfig() }
    }
}
