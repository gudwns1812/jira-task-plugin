package io.github.gudwns1812.jiratask.ui

import io.github.gudwns1812.jiratask.application.CredentialStore
import io.github.gudwns1812.jiratask.domain.jira.JiraNamedValue
import io.github.gudwns1812.jiratask.infrastructure.CredentialStorageError
import io.github.gudwns1812.jiratask.infrastructure.JiraTaskConfigStorage
import io.github.gudwns1812.jiratask.infrastructure.PolicyConfigRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 명령을 실행하기 전 설정이 충분한지 보는 규칙. 안내 문구와 환경 변수 우선순위를 함께 본다. */
class JiraTaskSettingsTest {

    private class FakeCredentials(private val token: String? = null, private val failure: Exception? = null) :
        CredentialStore {
        override fun save(baseUrl: String, email: String, apiToken: String) = Unit

        override fun get(baseUrl: String, email: String): String? {
            failure?.let { throw it }
            return token
        }
    }

    private fun repository() = PolicyConfigRepository(JiraTaskConfigStorage())

    private fun configuredRepository() = repository().apply {
        saveConfig(
            mapOf(
                "projectKey" to "S15P11A108",
                "jira" to mapOf(
                    "baseUrl" to "https://team.atlassian.net",
                    "email" to "gudwns1423@gmail.com",
                    "issueTypes" to mapOf("story" to mapOf("id" to "10001", "name" to "스토리")),
                    "statuses" to mapOf("story" to mapOf("start" to mapOf("id" to "21", "name" to "진행 중"))),
                ),
            ),
        )
    }

    private fun taskConfiguredRepository() = repository().apply {
        saveConfig(
            mapOf(
                "projectKey" to "S15P11A108",
                "jira" to mapOf(
                    "baseUrl" to "https://team.atlassian.net",
                    "email" to "gudwns1423@gmail.com",
                    "issueTypes" to mapOf("task" to mapOf("id" to "10002", "name" to "작업")),
                    "statuses" to mapOf(
                        "story" to mapOf("start" to mapOf("id" to "21", "name" to "진행 중")),
                        "task" to mapOf("done" to mapOf("id" to "31", "name" to "완료")),
                    ),
                ),
            ),
        )
    }

    private fun settings(
        repository: PolicyConfigRepository,
        credentials: CredentialStore = FakeCredentials("token"),
        environment: (String) -> String? = { null },
    ) = JiraTaskSettings(repository, credentials, environment)

    @Test
    fun `asks for setup when no project key is configured`() {
        val lookup = settings(repository()).forCommit()

        assertEquals(SettingsLookup.Missing(JiraTaskSettings.NO_PROJECT_KEY), lookup)
    }

    @Test
    fun `returns the project key for a commit`() {
        assertEquals(
            SettingsLookup.Ready(CommitSettings("S15P11A108")),
            settings(configuredRepository()).forCommit(),
        )
        assertEquals(
            SettingsLookup.Missing(JiraTaskSettings.NO_PROJECT_KEY),
            settings(repository()).forCommit(),
        )
    }

    @Test
    fun `reports only the token when the rest is configured`() {
        val lookup = settings(taskConfiguredRepository(), FakeCredentials(null)).forTaskTransition()

        val message = (lookup as SettingsLookup.Missing).message
        assertTrue(message.contains("JIRA_API_TOKEN"))
        assertTrue(message.contains("비밀번호 저장소"))
        assertTrue("나머지 설정은 문제로 보고하지 않는다", !message.contains("프로젝트 키"))
    }

    @Test
    fun `prefers the environment variables over the saved values`() {
        val lookup = settings(
            taskConfiguredRepository(),
            FakeCredentials("saved-token"),
            environment = { name ->
                when (name) {
                    "JIRA_EMAIL" -> "env@example.com"
                    "JIRA_API_TOKEN" -> "env-token"
                    else -> null
                }
            },
        ).forTaskTransition()

        val ready = lookup as SettingsLookup.Ready
        assertEquals("env@example.com", ready.value.email)
        assertEquals("env-token", ready.value.apiToken)
    }

    @Test
    fun `explains a credential store failure instead of crashing`() {
        val lookup = settings(taskConfiguredRepository(), FakeCredentials(failure = CredentialStorageError()))
            .forTaskTransition()

        assertTrue((lookup as SettingsLookup.Missing).message.contains("자격 증명"))
    }

    @Test
    fun `explains a broken config for every command`() {
        val storage = JiraTaskConfigStorage().apply { configJson = "{\"projectKey\": \"lowercase\"}" }
        val broken = PolicyConfigRepository(storage)

        listOf(
            settings(broken).forCommit(),
            settings(broken).forTaskTransition(),
        ).forEach { lookup ->
            assertTrue(lookup is SettingsLookup.Missing)
            assertTrue((lookup as SettingsLookup.Missing).message.contains("projectKey"))
        }
    }

    @Test
    fun `returns everything a task transition needs`() {
        val ready = settings(taskConfiguredRepository()).forTaskTransition() as SettingsLookup.Ready

        assertEquals(
            TaskTransitionSettings(
                projectKey = "S15P11A108",
                baseUrl = "https://team.atlassian.net",
                email = "gudwns1423@gmail.com",
                apiToken = "token",
                taskIssueTypeId = "10002",
                inProgressStatus = JiraNamedValue("21", "진행 중"),
            ),
            ready.value,
        )
    }

    @Test
    fun `names every missing value for a task transition`() {
        val missing = settings(configuredRepository()).forTaskTransition() as SettingsLookup.Missing

        assertTrue(missing.message.contains("작업 타입(설정 마법사)"))
        assertFalse("완료 상태는 더 이상 요구하지 않는다", missing.message.contains("작업 완료 상태"))
    }

    @Test
    fun `prefers the environment token for a task transition`() {
        val ready = settings(
            taskConfiguredRepository(),
            credentials = FakeCredentials(failure = CredentialStorageError()),
            environment = { name -> "env-token".takeIf { name == "JIRA_API_TOKEN" } },
        ).forTaskTransition() as SettingsLookup.Ready

        assertEquals("env-token", ready.value.apiToken)
    }
}
