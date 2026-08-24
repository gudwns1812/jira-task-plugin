package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.branch.GitRepositoryError
import io.github.gudwns1812.jiratask.domain.branch.GitRepositoryPort
import io.github.gudwns1812.jiratask.domain.jira.JiraAuthenticationError
import io.github.gudwns1812.jiratask.domain.jira.JiraIssue
import io.github.gudwns1812.jiratask.domain.jira.JiraNetworkError
import io.github.gudwns1812.jiratask.domain.jira.StoryIssueReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PROJECT_KEY = "S15P11A108"
private const val STORY_TYPE_ID = "10"
private const val IN_PROGRESS_STATUS_ID = "2"

private fun command(storyNumber: String = "100", name: String = "signup") = CreateBranchCommand(
    projectKey = PROJECT_KEY,
    storyNumber = storyNumber,
    name = name,
    storyIssueTypeId = STORY_TYPE_ID,
    inProgressStatusId = IN_PROGRESS_STATUS_ID,
)

private class FakeStoryIssueReader(
    private val issue: JiraIssue? = null,
    private val getIssueError: Exception? = null,
    private val transitionId: String? = "31",
    private val transitionError: Exception? = null,
) : StoryIssueReader {
    var transitioned = false
        private set

    override fun getIssue(issueKey: String): JiraIssue {
        getIssueError?.let { throw it }
        return issue ?: error("no issue configured")
    }

    override fun findTransitionId(issueKey: String, targetStatusId: String): String? = transitionId

    override fun transition(issueKey: String, transitionId: String) {
        transitionError?.let { throw it }
        transitioned = true
    }
}

private class FakeGitRepository(private val failure: GitRepositoryError? = null) : GitRepositoryPort {
    var switched: String? = null
        private set

    override fun ensureRepository() {
        failure?.let { throw it }
    }
    override fun ensureCleanWorkingTree() {}
    override fun ensureBranchAbsent(branchName: String) {}
    override fun createAndSwitch(branchName: String) { switched = branchName }
    override fun setCurrentTaskNumber(taskNumber: String) {}
    override fun getCurrentTaskNumber(): String? = null
    override fun ensureStagedChanges() {}
    override fun commit(message: String) {}
}

class CreateBranchTest {

    @Test
    fun `creates the branch and transitions when the story is not yet in progress`() {
        val reader = FakeStoryIssueReader(
            issue = JiraIssue("S15P11A108-100", STORY_TYPE_ID, "Story", "1", "To Do"),
        )
        val git = FakeGitRepository()

        val result = CreateBranchUseCase(reader, git).execute(command())

        assertEquals(CreateBranchResult.Success("feat/100-signup", "S15P11A108-100", true), result)
        assertEquals("feat/100-signup", git.switched)
        assertTrue(reader.transitioned)
    }

    @Test
    fun `succeeds without transitioning when the story is already in progress`() {
        val reader = FakeStoryIssueReader(
            issue = JiraIssue("S15P11A108-100", STORY_TYPE_ID, "Story", IN_PROGRESS_STATUS_ID, "In Progress"),
        )

        val result = CreateBranchUseCase(reader, FakeGitRepository()).execute(command())

        assertEquals(CreateBranchResult.Success("feat/100-signup", "S15P11A108-100", false), result)
    }

    @Test
    fun `keeps the branch as a partial success when no transition targets the status`() {
        val reader = FakeStoryIssueReader(
            issue = JiraIssue("S15P11A108-100", STORY_TYPE_ID, "Story", "1", "To Do"),
            transitionId = null,
        )

        val result = CreateBranchUseCase(reader, FakeGitRepository()).execute(command())

        assertTrue(result is CreateBranchResult.PartialSuccess)
        assertEquals("feat/100-signup", (result as CreateBranchResult.PartialSuccess).branchName)
    }

    @Test
    fun `keeps the branch as a partial success when the transition call fails`() {
        val reader = FakeStoryIssueReader(
            issue = JiraIssue("S15P11A108-100", STORY_TYPE_ID, "Story", "1", "To Do"),
            transitionError = JiraNetworkError(),
        )

        val result = CreateBranchUseCase(reader, FakeGitRepository()).execute(command())

        assertTrue(result is CreateBranchResult.PartialSuccess)
    }

    @Test
    fun `fails without touching git when the issue is not a story`() {
        val reader = FakeStoryIssueReader(
            issue = JiraIssue("S15P11A108-100", "11", "Task", "1", "To Do"),
        )
        val git = FakeGitRepository()

        val result = CreateBranchUseCase(reader, git).execute(command())

        assertEquals(1, (result as CreateBranchResult.Failure).exitCode)
        assertEquals(null, git.switched)
    }

    @Test
    fun `maps jira authentication errors to exit code 3`() {
        val reader = FakeStoryIssueReader(getIssueError = JiraAuthenticationError())

        val result = CreateBranchUseCase(reader, FakeGitRepository()).execute(command())

        assertEquals(3, (result as CreateBranchResult.Failure).exitCode)
    }

    @Test
    fun `maps jira network errors to exit code 4`() {
        val reader = FakeStoryIssueReader(getIssueError = JiraNetworkError())

        val result = CreateBranchUseCase(reader, FakeGitRepository()).execute(command())

        assertEquals(4, (result as CreateBranchResult.Failure).exitCode)
    }

    @Test
    fun `maps git errors to exit code 5 and preserves failure message`() {
        val reader = FakeStoryIssueReader(
            issue = JiraIssue("S15P11A108-100", STORY_TYPE_ID, "Story", "1", "To Do"),
        )
        val git = FakeGitRepository(failure = GitRepositoryError("커밋되지 않은 변경 사항이 있어 브랜치를 만들 수 없습니다."))

        val result = CreateBranchUseCase(reader, git).execute(command()) as CreateBranchResult.Failure

        assertEquals(5, result.exitCode)
        assertTrue(result.message.contains("커밋되지 않은"))
    }

    @Test
    fun `rejects an invalid story number before touching jira or git`() {
        val reader = FakeStoryIssueReader(getIssueError = IllegalStateException("should not be called"))

        val result = CreateBranchUseCase(reader, FakeGitRepository()).execute(command(storyNumber = "0"))

        assertEquals(1, (result as CreateBranchResult.Failure).exitCode)
    }

    @Test
    fun `rejects an invalid branch name slug`() {
        val result = CreateBranchUseCase(FakeStoryIssueReader(), FakeGitRepository()).execute(command(name = "Email Signup"))

        assertEquals(1, (result as CreateBranchResult.Failure).exitCode)
    }
}
