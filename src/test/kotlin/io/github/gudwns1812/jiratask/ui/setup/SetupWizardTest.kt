package io.github.gudwns1812.jiratask.ui.setup

import io.github.gudwns1812.jiratask.application.CredentialStore
import io.github.gudwns1812.jiratask.application.SetupCancelledError
import io.github.gudwns1812.jiratask.application.SetupChoice
import io.github.gudwns1812.jiratask.application.SetupConnection
import io.github.gudwns1812.jiratask.application.SetupInputError
import io.github.gudwns1812.jiratask.application.SetupPrompter
import io.github.gudwns1812.jiratask.application.SetupSelectionError
import io.github.gudwns1812.jiratask.application.SetupSummary
import io.github.gudwns1812.jiratask.application.toDisplayLines
import io.github.gudwns1812.jiratask.domain.jira.JiraAuthenticationError
import io.github.gudwns1812.jiratask.domain.jira.JiraIssueTypeWithStatuses
import io.github.gudwns1812.jiratask.domain.jira.JiraNamedValue
import io.github.gudwns1812.jiratask.domain.jira.JiraNetworkError
import io.github.gudwns1812.jiratask.domain.jira.JiraProject
import io.github.gudwns1812.jiratask.domain.jira.JiraSetupReader
import io.github.gudwns1812.jiratask.infrastructure.CredentialStorageError
import io.github.gudwns1812.jiratask.infrastructure.JiraTaskConfigStorage
import io.github.gudwns1812.jiratask.infrastructure.PolicyConfigRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 마법사 배선(유스케이스 + 설정 저장소 + 자격 증명 저장소)을 다이얼로그 없이 검증한다.
 * 다이얼로그 자체는 [SetupFormsTest]가 폼 단위로 확인한다.
 */
class SetupWizardTest {

    private val project = JiraProject("10000", "S15P11A108", "A108 프로젝트")

    private val issueTypes = listOf(
        JiraIssueTypeWithStatuses("10001", "스토리", listOf(JiraNamedValue("21", "진행 중"), JiraNamedValue("22", "완료"))),
        JiraIssueTypeWithStatuses("10002", "작업", listOf(JiraNamedValue("31", "할 일"), JiraNamedValue("32", "완료"))),
        JiraIssueTypeWithStatuses("10003", "에픽", listOf(JiraNamedValue("41", "진행 중"))),
    )

    private class FakeJira(
        private val projects: List<JiraProject>,
        private val issueTypes: List<JiraIssueTypeWithStatuses>,
        private val failuresBeforeSuccess: MutableList<Exception> = mutableListOf(),
        private val listProjectsError: Exception? = null,
    ) : JiraSetupReader {
        override fun verifyIdentity() {
            if (failuresBeforeSuccess.isNotEmpty()) {
                throw failuresBeforeSuccess.removeAt(0)
            }
        }

        override fun listProjects(): List<JiraProject> {
            listProjectsError?.let { throw it }
            return projects
        }

        override fun listIssueTypesWithStatuses(projectKey: String): List<JiraIssueTypeWithStatuses> = issueTypes
    }

    private class RecordingCredentialStore(private val failure: Exception? = null) : CredentialStore {
        val saved = mutableListOf<Triple<String, String, String>>()

        override fun save(baseUrl: String, email: String, apiToken: String) {
            failure?.let { throw it }
            saved += Triple(baseUrl, email, apiToken)
        }

        override fun get(baseUrl: String, email: String): String? = null
    }

    /** 대화 흐름을 스크립트로 대신하는 프롬프터. 선택은 항상 첫 번째 후보를 고른다. */
    private class ScriptedPrompter(
        private val connections: MutableList<Any> = mutableListOf(
            SetupConnection("https://team.atlassian.net", "gudwns1423@gmail.com", "token"),
        ),
        private val confirmAnswer: Boolean = true,
    ) : SetupPrompter {
        val errors = mutableListOf<String>()
        val successes = mutableListOf<String>()
        var confirmedSummary: SetupSummary? = null

        override fun askConnection(): SetupConnection = when (val next = connections.removeAt(0)) {
            is SetupConnection -> next
            is Exception -> throw next
            else -> throw IllegalStateException("지원하지 않는 스크립트 값")
        }

        override fun choose(message: String, choices: List<SetupChoice>): String = choices.first().value

        override fun confirm(summary: SetupSummary): Boolean {
            confirmedSummary = summary
            return confirmAnswer
        }

        override fun showSuccess(message: String) {
            successes += message
        }

        override fun showError(message: String) {
            errors += message
        }
    }

    private fun repository() = PolicyConfigRepository(JiraTaskConfigStorage())

    private fun wizard(
        prompter: SetupPrompter,
        repository: PolicyConfigRepository,
        credentials: CredentialStore,
        jira: JiraSetupReader = FakeJira(listOf(project), issueTypes),
    ) = SetupWizard(prompter, credentials, repository, createJira = { jira })

    @Test
    fun `saves the config and the api token when the flow completes`() {
        val repository = repository()
        val credentials = RecordingCredentialStore()
        val prompter = ScriptedPrompter()

        val result = wizard(prompter, repository, credentials).run()

        assertEquals(
            SetupWizardResult.Completed("S15P11A108", "스토리"),
            result,
        )
        val saved = repository.loadConfig()
        assertEquals("S15P11A108", saved.projectKey)
        assertEquals("https://team.atlassian.net", saved.jira?.baseUrl)
        assertEquals("gudwns1423@gmail.com", saved.jira?.email)
        assertEquals(JiraNamedValue("10001", "스토리"), saved.jira?.issueTypes?.story)
        assertEquals(JiraNamedValue("10002", "작업"), saved.jira?.issueTypes?.task)
        assertEquals(JiraNamedValue("10003", "에픽"), saved.jira?.issueTypes?.epic)
        assertEquals(
            listOf(Triple("https://team.atlassian.net", "gudwns1423@gmail.com", "token")),
            credentials.saved,
        )
    }

    @Test
    fun `saves nothing when the connection dialog is cancelled`() {
        val repository = repository()
        val credentials = RecordingCredentialStore()
        val prompter = ScriptedPrompter(mutableListOf(SetupCancelledError()))

        val result = wizard(prompter, repository, credentials).run()

        assertEquals(SetupWizardResult.Cancelled, result)
        assertFalse(repository.hasConfig())
        assertTrue(credentials.saved.isEmpty())
    }

    @Test
    fun `saves nothing when the summary is rejected`() {
        val repository = repository()
        val credentials = RecordingCredentialStore()
        val prompter = ScriptedPrompter(confirmAnswer = false)

        val result = wizard(prompter, repository, credentials).run()

        assertEquals(SetupWizardResult.Cancelled, result)
        assertFalse(repository.hasConfig())
        assertTrue(credentials.saved.isEmpty())
    }

    @Test
    fun `shows the summary without the email or the api token`() {
        val prompter = ScriptedPrompter()

        wizard(prompter, repository(), RecordingCredentialStore()).run()

        val lines = prompter.confirmedSummary!!.toDisplayLines()
        assertTrue(lines.none { (_, value) -> value.contains("gudwns1423") || value.contains("token") })
        assertEquals("Jira 주소" to "https://team.atlassian.net", lines.first())
    }

    @Test
    fun `retries after an authentication failure and saves the working credentials`() {
        val repository = repository()
        val credentials = RecordingCredentialStore()
        val prompter = ScriptedPrompter(
            mutableListOf(
                SetupConnection("https://team.atlassian.net", "gudwns1423@gmail.com", "wrong"),
                SetupConnection("https://team.atlassian.net", "gudwns1423@gmail.com", "right"),
            ),
        )
        val jira = FakeJira(
            listOf(project),
            issueTypes,
            failuresBeforeSuccess = mutableListOf(JiraAuthenticationError("Jira 인증에 실패했습니다.")),
        )

        val result = wizard(prompter, repository, credentials, jira).run()

        assertTrue(result is SetupWizardResult.Completed)
        assertEquals(1, prompter.errors.size)
        assertEquals("right", credentials.saved.single().third)
    }

    @Test
    fun `reports the reason and saves nothing when no project is accessible`() {
        val repository = repository()
        val credentials = RecordingCredentialStore()

        val result = wizard(
            ScriptedPrompter(),
            repository,
            credentials,
            FakeJira(emptyList(), issueTypes),
        ).run()

        assertEquals(SetupWizardResult.Failed("접근할 수 있는 Jira 프로젝트가 없습니다."), result)
        assertFalse(repository.hasConfig())
        assertTrue(credentials.saved.isEmpty())
    }

    @Test
    fun `reports the reason when a required issue type is missing`() {
        val repository = repository()

        val result = wizard(
            ScriptedPrompter(),
            repository,
            RecordingCredentialStore(),
            FakeJira(listOf(project), issueTypes.filterNot { it.name == "에픽" }),
        ).run()

        assertTrue(result is SetupWizardResult.Failed)
        assertTrue((result as SetupWizardResult.Failed).message.contains("에픽"))
        assertFalse(repository.hasConfig())
    }

    @Test
    fun `hides technical details of a network failure behind the generic message`() {
        val repository = repository()

        val result = wizard(
            ScriptedPrompter(),
            repository,
            RecordingCredentialStore(),
            FakeJira(listOf(project), issueTypes, listProjectsError = JiraNetworkError("connect timed out to 10.0.0.1")),
        ).run()

        assertEquals(SetupWizardResult.Failed(SetupFailureMessages.FALLBACK), result)
        assertFalse(repository.hasConfig())
    }

    @Test
    fun `does not save the config when the api token cannot be stored`() {
        val repository = repository()
        val credentials = RecordingCredentialStore(CredentialStorageError())

        val result = wizard(ScriptedPrompter(), repository, credentials).run()

        assertTrue(result is SetupWizardResult.Failed)
        assertTrue((result as SetupWizardResult.Failed).message.contains("자격 증명"))
        assertFalse("자격 증명 저장이 실패하면 설정도 남지 않아야 한다", repository.hasConfig())
    }

    @Test
    fun `maps failures to messages the same way the CLI does`() {
        assertEquals(
            "접근할 수 있는 Jira 프로젝트가 없습니다.",
            SetupFailureMessages.of(SetupSelectionError("접근할 수 있는 Jira 프로젝트가 없습니다.")),
        )
        assertEquals("Jira 주소를 입력하세요.", SetupFailureMessages.of(SetupInputError("Jira 주소를 입력하세요.")))
        assertEquals(
            "운영체제 자격 증명 저장소에 API 토큰을 저장하거나 읽지 못했습니다.",
            SetupFailureMessages.of(CredentialStorageError()),
        )
        assertEquals(
            "예상하지 못한 예외의 세부 내용은 사용자에게 그대로 보여주지 않는다",
            SetupFailureMessages.FALLBACK,
            SetupFailureMessages.of(RuntimeException("connect timed out to 10.0.0.1")),
        )
    }

    @Test
    fun `overwrites a previous setup when the wizard runs again`() {
        val repository = repository()
        repository.saveConfig(mapOf("projectKey" to "OLD", "jira" to mapOf("baseUrl" to "https://old.atlassian.net")))

        wizard(ScriptedPrompter(), repository, RecordingCredentialStore()).run()

        val saved = repository.loadConfig()
        assertEquals("S15P11A108", saved.projectKey)
        assertEquals("https://team.atlassian.net", saved.jira?.baseUrl)
        assertEquals("10000", saved.jira?.project?.id)
    }
}
