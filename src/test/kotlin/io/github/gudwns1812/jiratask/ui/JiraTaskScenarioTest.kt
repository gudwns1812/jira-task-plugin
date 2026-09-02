package io.github.gudwns1812.jiratask.ui

import io.github.gudwns1812.jiratask.application.CreateCommitCommand
import io.github.gudwns1812.jiratask.application.CreateCommitResult
import io.github.gudwns1812.jiratask.application.CreateCommitUseCase
import io.github.gudwns1812.jiratask.application.CredentialStore
import io.github.gudwns1812.jiratask.application.SetupChoice
import io.github.gudwns1812.jiratask.application.SetupConnection
import io.github.gudwns1812.jiratask.application.SetupPrompter
import io.github.gudwns1812.jiratask.application.SetupSummary
import io.github.gudwns1812.jiratask.application.StartTaskResult
import io.github.gudwns1812.jiratask.application.StartTaskUseCase
import io.github.gudwns1812.jiratask.domain.jira.JiraIssueTypeWithStatuses
import io.github.gudwns1812.jiratask.domain.jira.JiraNamedValue
import io.github.gudwns1812.jiratask.domain.jira.JiraProject
import io.github.gudwns1812.jiratask.domain.jira.JiraSetupReader
import io.github.gudwns1812.jiratask.infrastructure.GitRepository
import io.github.gudwns1812.jiratask.infrastructure.JiraTaskConfigStorage
import io.github.gudwns1812.jiratask.infrastructure.PolicyConfigRepository
import io.github.gudwns1812.jiratask.ui.commit.CommitReports
import io.github.gudwns1812.jiratask.ui.commit.JiraCommitMessages
import io.github.gudwns1812.jiratask.ui.setup.SetupWizard
import io.github.gudwns1812.jiratask.ui.setup.SetupWizardResult
import io.github.gudwns1812.jiratask.ui.task.CurrentTaskText
import io.github.gudwns1812.jiratask.ui.task.StartTaskReports
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * 설정 마법사 → 이슈 초안 검증/미리보기 → 브랜치 생성 → 작업 시작 → 커밋까지의 전체 흐름을
 * 실제 임시 Git 저장소와 가짜 Jira로 이어서 확인한다(T25). 다이얼로그는 대본으로 대신한다.
 */
class JiraTaskScenarioTest {

    private lateinit var directory: File
    private lateinit var repository: PolicyConfigRepository

    private val project = JiraProject("10000", "S15P11A108", "A108 프로젝트")

    private val issueTypes = listOf(
        JiraIssueTypeWithStatuses("10001", "스토리", listOf(JiraNamedValue("21", "진행 중"))),
        JiraIssueTypeWithStatuses("10002", "작업", listOf(JiraNamedValue("31", "완료"))),
        JiraIssueTypeWithStatuses("10003", "에픽", listOf(JiraNamedValue("41", "진행 중"))),
    )

    private class ScriptedPrompter : SetupPrompter {
        override fun askConnection(): SetupConnection =
            SetupConnection("https://team.atlassian.net", "gudwns1423@gmail.com", "token")

        override fun choose(message: String, choices: List<SetupChoice>): String = choices.first().value

        override fun confirm(summary: SetupSummary): Boolean = true

        override fun showSuccess(message: String) = Unit

        override fun showError(message: String) = Unit
    }

    private inner class FakeJira : JiraSetupReader {
        override fun verifyIdentity() = Unit

        override fun listProjects(): List<JiraProject> = listOf(project)

        override fun listIssueTypesWithStatuses(projectKey: String): List<JiraIssueTypeWithStatuses> = issueTypes
    }

    private class InMemoryCredentials : CredentialStore {
        private val tokens = mutableMapOf<String, String>()

        override fun save(baseUrl: String, email: String, apiToken: String) {
            tokens["$baseUrl|$email"] = apiToken
        }

        override fun get(baseUrl: String, email: String): String? = tokens["$baseUrl|$email"]
    }

    @Before
    fun setUp() {
        directory = createRepository()
        repository = PolicyConfigRepository(JiraTaskConfigStorage())
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `runs the whole flow from setup to commit`() {
        val jira = FakeJira()
        val credentials = InMemoryCredentials()

        // 1. 설정 마법사
        val setup = SetupWizard(ScriptedPrompter(), credentials, repository, createJira = { jira }).run()
        assertEquals(SetupWizardResult.Completed("S15P11A108", "스토리"), setup)

        val settings = JiraTaskSettings(repository, credentials) { null }
        val git = GitRepository(directory)

        // 2. 작업 시작
        val startResult = StartTaskUseCase(git).execute("257")
        assertEquals(StartTaskResult.Success("257"), startResult)
        assertEquals("Jira: 257", CurrentTaskText.of(git.getCurrentTaskNumber()))
        assertTrue(StartTaskReports.of(startResult).message.contains("257"))

        // 3. 커밋 (메시지 접두는 커밋 다이얼로그가 하는 것과 같은 규칙으로 확인)
        val commitSettings = (settings.forCommit() as SettingsLookup.Ready).value
        assertEquals(
            "[S15P11A108-257] 회원가입 폼 구현",
            JiraCommitMessages.withIssueKey("회원가입 폼 구현", "${commitSettings.projectKey}-257"),
        )

        File(directory, "signup.txt").writeText("form\n")
        runGit(directory, "add", "signup.txt")
        val commitResult = CreateCommitUseCase(git).execute(
            CreateCommitCommand(commitSettings.projectKey, "회원가입 폼 구현"),
        )
        assertEquals(
            CreateCommitResult.Success("S15P11A108-257", "[S15P11A108-257] 회원가입 폼 구현"),
            commitResult,
        )
        assertEquals(
            "[S15P11A108-257] 회원가입 폼 구현\n",
            runGit(directory, "log", "-1", "--pretty=%s"),
        )
        assertTrue(CommitReports.of(commitResult).message.contains("S15P11A108-257"))
    }

    @Test
    fun `stops before touching jira when the setup was cancelled`() {
        val settings = JiraTaskSettings(repository, InMemoryCredentials()) { null }

        val lookup = settings.forTaskTransition()

        assertTrue(lookup is SettingsLookup.Missing)
        assertTrue((lookup as SettingsLookup.Missing).message.startsWith("작업 상태 전환 설정이 없습니다"))
    }

    @Test
    fun `refuses to commit before a task number is stored`() {
        val jira = FakeJira()
        SetupWizard(ScriptedPrompter(), InMemoryCredentials(), repository, createJira = { jira }).run()
        File(directory, "signup.txt").writeText("form\n")
        runGit(directory, "add", "signup.txt")

        val result = CreateCommitUseCase(GitRepository(directory))
            .execute(CreateCommitCommand("S15P11A108", "회원가입 폼 구현"))

        assertTrue(result is CreateCommitResult.Failure)
        assertTrue((result as CreateCommitResult.Failure).message.contains("현재 작업 번호가 없습니다"))
    }

    private fun createRepository(): File {
        val created = Files.createTempDirectory("jira-task-plugin-scenario-").toFile()
        runGit(created, "init")
        runGit(created, "config", "user.email", "test@example.com")
        runGit(created, "config", "user.name", "Test")
        File(created, "README.md").writeText("test\n")
        runGit(created, "add", "README.md")
        runGit(created, "commit", "-m", "initial")
        return created
    }

    private fun runGit(workingDirectory: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(workingDirectory).start()
        val stdout = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val stderr = process.errorStream.bufferedReader(Charsets.UTF_8).readText()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "git 명령이 시간 내에 끝나지 않았습니다." }
        check(process.exitValue() == 0) { "git ${args.joinToString(" ")} 실패: $stderr" }
        return stdout
    }
}
