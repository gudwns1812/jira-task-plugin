package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.jira.JiraAuthenticationError
import io.github.gudwns1812.jiratask.domain.jira.JiraIssueTypeWithStatuses
import io.github.gudwns1812.jiratask.domain.jira.JiraNamedValue
import io.github.gudwns1812.jiratask.domain.jira.JiraProject
import io.github.gudwns1812.jiratask.domain.jira.JiraSetupReader
import io.github.gudwns1812.jiratask.domain.policy.JiraSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private val DEFAULT_ISSUE_TYPES = listOf(
    JiraIssueTypeWithStatuses("10", "스토리", listOf(JiraNamedValue("3", "진행 중"))),
    JiraIssueTypeWithStatuses("11", "작업", listOf(JiraNamedValue("4", "완료"))),
    JiraIssueTypeWithStatuses("12", "에픽", listOf(JiraNamedValue("5", "해야 할 일"))),
)

private class FakePrompter(
    private val credentials: List<String> = listOf("secret"),
    private val selections: List<String> = listOf("10001", "3", "4"),
    private val confirmResult: Boolean = true,
) : SetupPrompter {
    private var credentialIndex = 0
    private var selectionIndex = 0
    val errors = mutableListOf<String>()
    val successes = mutableListOf<String>()

    override fun askConnection() =
        SetupConnection("https://team.atlassian.net", "user@example.com", credentials[credentialIndex++])

    override fun choose(message: String, choices: List<SetupChoice>): String = selections[selectionIndex++]

    override fun confirm(summary: SetupSummary): Boolean = confirmResult

    override fun showSuccess(message: String) {
        successes.add(message)
    }

    override fun showError(message: String) {
        errors.add(message)
    }
}

private fun fakeJiraReader(
    verifyIdentity: () -> Unit = {},
    issueTypes: List<JiraIssueTypeWithStatuses> = DEFAULT_ISSUE_TYPES,
): JiraSetupReader = object : JiraSetupReader {
    override fun verifyIdentity() = verifyIdentity()
    override fun listProjects(): List<JiraProject> = listOf(JiraProject("10001", "S15P11A108", "특화 프로젝트"))
    override fun listIssueTypesWithStatuses(projectKey: String): List<JiraIssueTypeWithStatuses> = issueTypes
}

private class FakeCredentialStore : CredentialStore {
    val saved = mutableListOf<Triple<String, String, String>>()
    override fun save(baseUrl: String, email: String, apiToken: String) {
        saved.add(Triple(baseUrl, email, apiToken))
    }
    override fun get(baseUrl: String, email: String): String? = null
}

class RunInitialSetupTest {

    @Test
    fun `selects jira metadata and saves config without secrets while keeping the token in credentials`() {
        val prompter = FakePrompter()
        var savedProjectKey: String? = null
        var savedJira: JiraSettings? = null
        val credentials = FakeCredentialStore()

        val result = RunInitialSetupUseCase(
            prompter,
            { fakeJiraReader() },
            { projectKey, jira -> savedProjectKey = projectKey; savedJira = jira },
            credentials,
        ).execute()

        assertEquals(InitialSetupResult("S15P11A108", "스토리", "진행 중"), result)
        assertEquals("S15P11A108", savedProjectKey)
        assertEquals(
            JiraSettings(
                baseUrl = "https://team.atlassian.net",
                email = "user@example.com",
                project = io.github.gudwns1812.jiratask.domain.policy.JiraProjectRef("10001", "특화 프로젝트"),
                issueTypes = JiraSettings.IssueTypes(
                    story = JiraNamedValue("10", "스토리"),
                    task = JiraNamedValue("11", "작업"),
                    epic = JiraNamedValue("12", "에픽"),
                ),
                statuses = JiraSettings.Statuses(
                    story = JiraSettings.Statuses.StoryStatuses(JiraNamedValue("3", "진행 중")),
                    task = JiraSettings.Statuses.TaskStatuses(JiraNamedValue("4", "완료")),
                ),
            ),
            savedJira,
        )
        assertEquals(listOf(Triple("https://team.atlassian.net", "user@example.com", "secret")), credentials.saved)
        assertTrue(prompter.successes.contains("✓ Jira 연결 성공"))
    }

    @Test
    fun `retries credentials after an authentication failure and only saves the working token`() {
        var attempt = 0
        val prompter = FakePrompter(credentials = listOf("wrong", "secret"))
        val credentials = FakeCredentialStore()

        RunInitialSetupUseCase(
            prompter,
            {
                fakeJiraReader(verifyIdentity = {
                    attempt++
                    if (attempt == 1) throw JiraAuthenticationError()
                })
            },
            { _, _ -> },
            credentials,
        ).execute()

        assertEquals(listOf("Jira 인증에 실패했습니다. 이메일과 API 토큰을 다시 확인하세요."), prompter.errors)
        assertEquals(1, credentials.saved.size)
        assertEquals("secret", credentials.saved.single().third)
    }

    @Test
    fun `does not save anything when the user declines the summary`() {
        val credentials = FakeCredentialStore()
        var saveConfigCalled = false

        assertThrows(SetupCancelledError::class.java) {
            RunInitialSetupUseCase(
                FakePrompter(confirmResult = false),
                { fakeJiraReader() },
                { _, _ -> saveConfigCalled = true },
                credentials,
            ).execute()
        }

        assertFalse(saveConfigCalled)
        assertTrue(credentials.saved.isEmpty())
    }

    @Test
    fun `fails when the selected project id does not exist`() {
        assertThrows(SetupSelectionError::class.java) {
            RunInitialSetupUseCase(
                FakePrompter(selections = listOf("999", "3", "4")),
                { fakeJiraReader() },
                { _, _ -> },
                FakeCredentialStore(),
            ).execute()
        }
    }

    @Test
    fun `finds story, task, and epic issue types by their English names too`() {
        val result = RunInitialSetupUseCase(
            FakePrompter(),
            {
                fakeJiraReader(
                    issueTypes = listOf(
                        JiraIssueTypeWithStatuses("10", "Story", listOf(JiraNamedValue("3", "진행 중"))),
                        JiraIssueTypeWithStatuses("11", "Task", listOf(JiraNamedValue("4", "완료"))),
                        JiraIssueTypeWithStatuses("12", "Epic", listOf(JiraNamedValue("5", "해야 할 일"))),
                    ),
                )
            },
            { _, _ -> },
            FakeCredentialStore(),
        ).execute()

        assertEquals("Story", result.storyIssueType)
    }

    @Test
    fun `fails immediately without saving when the story issue type is missing`() {
        var saveConfigCalled = false

        assertThrows(SetupSelectionError::class.java) {
            RunInitialSetupUseCase(
                FakePrompter(),
                {
                    fakeJiraReader(
                        issueTypes = listOf(
                            JiraIssueTypeWithStatuses("11", "작업", listOf(JiraNamedValue("4", "완료"))),
                            JiraIssueTypeWithStatuses("12", "에픽", listOf(JiraNamedValue("5", "해야 할 일"))),
                        ),
                    )
                },
                { _, _ -> saveConfigCalled = true },
                FakeCredentialStore(),
            ).execute()
        }

        assertFalse(saveConfigCalled)
    }

    @Test
    fun `fails immediately when multiple issue types match the same name`() {
        var saveConfigCalled = false

        assertThrows(SetupSelectionError::class.java) {
            RunInitialSetupUseCase(
                FakePrompter(),
                {
                    fakeJiraReader(
                        issueTypes = listOf(
                            JiraIssueTypeWithStatuses("10", "스토리", listOf(JiraNamedValue("3", "진행 중"))),
                            JiraIssueTypeWithStatuses("13", "Story", listOf(JiraNamedValue("3", "진행 중"))),
                            JiraIssueTypeWithStatuses("11", "작업", listOf(JiraNamedValue("4", "완료"))),
                            JiraIssueTypeWithStatuses("12", "에픽", listOf(JiraNamedValue("5", "해야 할 일"))),
                        ),
                    )
                },
                { _, _ -> saveConfigCalled = true },
                FakeCredentialStore(),
            ).execute()
        }

        assertFalse(saveConfigCalled)
    }
}
