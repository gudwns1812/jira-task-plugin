package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.mr.CreatedMergeRequest
import io.github.gudwns1812.jiratask.domain.mr.GitLabAuthenticationError
import io.github.gudwns1812.jiratask.domain.mr.GitLabError
import io.github.gudwns1812.jiratask.domain.mr.GitLabNetworkError
import io.github.gudwns1812.jiratask.domain.mr.MergeRequestCommand
import io.github.gudwns1812.jiratask.domain.mr.MergeRequestPort
import io.github.gudwns1812.jiratask.domain.mr.OpenMergeRequest
import io.github.gudwns1812.jiratask.ui.commit.CommitIssueKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // 커밋을 못 읽으면 키도 없다. 범위를 잘못 잡았을 때의 결과를 그대로 흉내낸다.
        issueKeysOf = { subjects -> if (subjects.isEmpty()) emptyList() else keys },
        summaryOf = { null },
    )

    private fun state(
        branch: String? = "feat/signup",
        remoteUrl: String? = "git@lab.ssafy.com:s15/A108.git",
        isPushed: Boolean = true,
        commits: List<String> = listOf("S15P11A108-257 회원가입"),
        commitsOf: (String) -> List<String> = { commits },
        remoteBranches: List<String> = emptyList(),
    ) = BranchState(branch, remoteUrl, isPushed, commitsOf, remoteBranches)

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
    fun `counts commits from the target branch it settled on`() {
        // `origin/main`을 가정하면 기본 브랜치가 다른 저장소에서 커밋도 이슈 키도 사라진다.
        val asked = mutableListOf<String>()
        val state = state(commitsOf = { target -> asked += target; listOf("S15P11A108-257 회원가입") })

        val proposal = readyOf(useCase(FakeGitLab(default = "develop"), listOf("S15P11A108-257")).prepare(state))

        assertEquals(listOf("develop"), asked)
        assertEquals("develop", proposal.targetBranch)
        assertTrue(proposal.description.contains("S15P11A108-257"))
    }

    @Test
    fun `counts commits from the fallback target when gitlab names no default branch`() {
        val asked = mutableListOf<String>()
        val state = state(commitsOf = { target -> asked += target; emptyList() })

        useCase(FakeGitLab(default = null)).prepare(state)

        assertEquals(listOf(CreateMergeRequestUseCase.DEFAULT_TARGET), asked)
    }

    @Test
    fun `offers the remote branches as targets with the default first`() {
        val state = state(remoteBranches = listOf("main", "backend-dev", "feat/signup"))

        val proposal = readyOf(useCase(FakeGitLab(default = "develop")).prepare(state))

        // 기본값이 맨 앞. 원본 브랜치는 자기 자신이라 뺀다.
        assertEquals(listOf("develop", "main", "backend-dev"), proposal.targetCandidates)
    }

    @Test
    fun `does not repeat the default branch among the candidates`() {
        val state = state(remoteBranches = listOf("main", "develop"))

        assertEquals(listOf("develop", "main"), readyOf(useCase(FakeGitLab(default = "develop")).prepare(state)).targetCandidates)
    }

    @Test
    fun `redraws the body from whichever target the user picks`() {
        // 통합 브랜치를 쓰는 팀에서 기본 브랜치를 기준으로 삼으면 남의 커밋이 딸려 온다.
        val perTarget = mapOf(
            "main" to listOf("S15P11A601-100 남의 커밋", "S15P11A601-266 내 커밋"),
            "backend-dev" to listOf("S15P11A601-266 내 커밋"),
        )
        val state = state(commitsOf = { target -> perTarget.getValue(target) })
        val useCase = CreateMergeRequestUseCase(
            gitlab = FakeGitLab(default = "main"),
            issueKeysOf = { subjects -> subjects.flatMap { CommitIssueKeys.all(it) }.distinct() },
        )

        val first = readyOf(useCase.prepare(state))
        val picked = useCase.redraft(state, first, "backend-dev")

        assertTrue("기본값은 남의 작업 키를 담는다", first.description.contains("S15P11A601-100"))
        assertEquals("backend-dev", picked.targetBranch)
        assertFalse("고른 뒤에는 사라진다", picked.description.contains("S15P11A601-100"))
        assertEquals(listOf("S15P11A601-266"), picked.issueKeys)
    }

    @Test
    fun `writes the keys alone, not the commits that carried them`() {
        val state = state(commitsOf = { listOf("S15P11A601-266 내 작업", "빌드 고침") })
        val useCase = CreateMergeRequestUseCase(
            gitlab = FakeGitLab(),
            issueKeysOf = { subjects -> subjects.flatMap { CommitIssueKeys.all(it) }.distinct() },
        )

        val proposal = readyOf(useCase.prepare(state))

        assertEquals(listOf("### 연결된 작업", "- S15P11A601-266"), proposal.description.lines())
        assertFalse("커밋 글은 본문에 넣지 않는다", proposal.description.contains("내 작업"))
    }

    @Test
    fun `still prepares when the commits cannot be read at all`() {
        val proposal = readyOf(useCase(FakeGitLab()).prepare(state(commitsOf = { throw IllegalStateException("git 없음") })))

        assertEquals("feat/signup", proposal.sourceBranch)
        assertTrue(proposal.issueKeys.isEmpty())
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
