package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.mr.CreatedMergeRequest
import io.github.gudwns1812.jiratask.domain.mr.GitLabAuthenticationError
import io.github.gudwns1812.jiratask.domain.mr.GitLabError
import io.github.gudwns1812.jiratask.domain.mr.GitLabNetworkError
import io.github.gudwns1812.jiratask.domain.mr.MergeRequestCommand
import io.github.gudwns1812.jiratask.domain.mr.MergeRequestPort
import io.github.gudwns1812.jiratask.domain.mr.OpenMergeRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 브랜치 상태에서 MR 초안까지, 그리고 만들 수 없는 경우를 어떻게 알리는가. */
class CreateMergeRequestTest {

    private class FakeGitLab(
        var existing: OpenMergeRequest? = null,
        var default: String? = "develop",
        var failure: Exception? = null,
    ) : MergeRequestPort {
        val created = mutableListOf<MergeRequestCommand>()

        override fun openMergeRequest(projectPath: String, sourceBranch: String): OpenMergeRequest? = existing

        override fun defaultBranch(projectPath: String): String? = default

        override fun create(command: MergeRequestCommand): CreatedMergeRequest {
            failure?.let { throw it }
            created += command
            return CreatedMergeRequest("https://lab.ssafy.com/a/b/-/merge_requests/7")
        }
    }

    private fun useCase(gitlab: MergeRequestPort, keys: List<String> = emptyList()) = CreateMergeRequestUseCase(
        gitlab = gitlab,
        issueKeysOf = { keys },
        summaryOf = { null },
        jiraBaseUrl = "https://team.atlassian.net",
    )

    private fun state(
        branch: String? = "feat/signup",
        remoteUrl: String? = "git@lab.ssafy.com:s15/A108.git",
        isPushed: Boolean = true,
        commits: List<String> = listOf("S15P11A108-257 회원가입"),
    ) = BranchState(branch, remoteUrl, isPushed, commits)

    private fun readyOf(prepare: MergeRequestPrepare) = (prepare as MergeRequestPrepare.Ready).proposal

    private fun blockedOf(prepare: MergeRequestPrepare) = (prepare as MergeRequestPrepare.Blocked).message

    @Test
    fun `prepares a draft from the branch and its commits`() {
        val proposal = readyOf(useCase(FakeGitLab(), listOf("S15P11A108-257")).prepare(state()))

        assertEquals("s15/A108", proposal.projectPath)
        assertEquals("feat/signup", proposal.sourceBranch)
        assertEquals("develop", proposal.targetBranch)
        assertEquals(listOf("S15P11A108-257"), proposal.issueKeys)
        assertTrue(proposal.description.contains("S15P11A108-257"))
        assertNull(proposal.existingUrl)
    }

    @Test
    fun `falls back to main when gitlab does not name a default branch`() {
        val proposal = readyOf(useCase(FakeGitLab(default = null)).prepare(state()))

        assertEquals(CreateMergeRequestUseCase.DEFAULT_TARGET, proposal.targetBranch)
    }

    @Test
    fun `points at the merge request that is already open`() {
        val open = OpenMergeRequest("https://lab.ssafy.com/a/b/-/merge_requests/3", "이미 있음")

        val proposal = readyOf(useCase(FakeGitLab(existing = open)).prepare(state()))

        assertEquals(open.webUrl, proposal.existingUrl)
    }

    @Test
    fun `still prepares when the open-merge-request check itself fails`() {
        // 확인하지 못한 것 때문에 MR을 못 만들게 하지 않는다.
        val gitlab = object : MergeRequestPort by FakeGitLab() {
            override fun openMergeRequest(projectPath: String, sourceBranch: String) = throw GitLabNetworkError()
        }

        assertNull(readyOf(useCase(gitlab).prepare(state())).existingUrl)
    }

    @Test
    fun `refuses a branch that was never pushed`() {
        val message = blockedOf(useCase(FakeGitLab()).prepare(state(isPushed = false)))

        assertTrue(message.contains("푸시"))
    }

    @Test
    fun `refuses a repository with no origin`() {
        assertTrue(blockedOf(useCase(FakeGitLab()).prepare(state(remoteUrl = null))).contains("remote"))
    }

    @Test
    fun `refuses a remote that is not a gitlab project`() {
        val message = blockedOf(useCase(FakeGitLab()).prepare(state(remoteUrl = "not a url")))

        assertTrue(message.contains("GitLab"))
    }

    @Test
    fun `refuses a detached head with no branch name`() {
        assertTrue(blockedOf(useCase(FakeGitLab()).prepare(state(branch = null))).contains("브랜치"))
    }

    @Test
    fun `prepares a draft even when no commit names an issue`() {
        // 키가 없다고 MR을 막지 않는다. 화면이 안내하고 사용자가 본문에 적으면 된다.
        val proposal = readyOf(useCase(FakeGitLab(), keys = emptyList()).prepare(state(commits = listOf("작업 중"))))

        assertTrue(proposal.issueKeys.isEmpty())
        assertTrue(proposal.title.isNotBlank())
    }

    @Test
    fun `creates the merge request with what the user confirmed`() {
        val gitlab = FakeGitLab()
        val proposal = readyOf(useCase(gitlab).prepare(state()))

        val result = useCase(gitlab).create(proposal, " 새 제목 ", "본문")

        assertTrue(result is MergeRequestResult.Created)
        assertEquals("새 제목", gitlab.created.single().title)
        assertEquals("feat/signup", gitlab.created.single().sourceBranch)
        assertEquals("develop", gitlab.created.single().targetBranch)
    }

    @Test
    fun `refuses to create without a title`() {
        val gitlab = FakeGitLab()
        val proposal = readyOf(useCase(gitlab).prepare(state()))

        val result = useCase(gitlab).create(proposal, "   ", "본문")

        assertTrue((result as MergeRequestResult.Failure).message.contains("제목"))
        assertTrue("GitLab을 부르지도 않는다", gitlab.created.isEmpty())
    }

    @Test
    fun `refuses to open a merge request onto the same branch`() {
        val gitlab = FakeGitLab(default = "feat/signup")
        val proposal = readyOf(useCase(gitlab).prepare(state()))

        val result = useCase(gitlab).create(proposal, "제목", "본문")

        assertTrue((result as MergeRequestResult.Failure).message.contains("같습니다"))
        assertTrue(gitlab.created.isEmpty())
    }

    @Test
    fun `reports what gitlab said instead of a generic failure`() {
        val gitlab = FakeGitLab(failure = GitLabError("source_branch can't be blank"))
        val proposal = readyOf(useCase(FakeGitLab()).prepare(state()))

        val result = useCase(gitlab).create(proposal, "제목", "본문")

        assertEquals("source_branch can't be blank", (result as MergeRequestResult.Failure).message)
    }

    @Test
    fun `separates a permission failure from a network failure`() {
        val proposal = readyOf(useCase(FakeGitLab()).prepare(state()))

        val denied = useCase(FakeGitLab(failure = GitLabAuthenticationError())).create(proposal, "제목", "본문")
        val offline = useCase(FakeGitLab(failure = GitLabNetworkError())).create(proposal, "제목", "본문")

        assertTrue((denied as MergeRequestResult.Failure).message.contains("인증"))
        assertTrue((offline as MergeRequestResult.Failure).message.contains("통신"))
    }

    @Test
    fun `survives an error nobody planned for`() {
        val gitlab = FakeGitLab(failure = IllegalStateException("boom"))
        val proposal = readyOf(useCase(FakeGitLab()).prepare(state()))

        assertTrue(useCase(gitlab).create(proposal, "제목", "본문") is MergeRequestResult.Failure)
    }
}
