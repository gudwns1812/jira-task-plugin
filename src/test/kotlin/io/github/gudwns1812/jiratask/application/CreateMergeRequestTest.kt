package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.mr.CreatedMergeRequest
import io.github.gudwns1812.jiratask.domain.mr.DraftOutcome
import io.github.gudwns1812.jiratask.domain.mr.DraftRequest
import io.github.gudwns1812.jiratask.domain.mr.DraftWriter
import io.github.gudwns1812.jiratask.domain.mr.GitLabAuthenticationError
import io.github.gudwns1812.jiratask.domain.mr.GitLabError
import io.github.gudwns1812.jiratask.domain.mr.GitLabIdentity
import io.github.gudwns1812.jiratask.domain.mr.GitLabNetworkError
import io.github.gudwns1812.jiratask.domain.mr.GitLabPermissionError
import io.github.gudwns1812.jiratask.domain.mr.GitLabProjectInfo
import io.github.gudwns1812.jiratask.domain.mr.MergeRequestCommand
import io.github.gudwns1812.jiratask.domain.mr.MergeRequestPort
import io.github.gudwns1812.jiratask.domain.mr.OpenMergeRequest
import io.github.gudwns1812.jiratask.domain.mr.WrittenDraft
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
        var projectTemplate: String? = null,
    ) : MergeRequestPort {
        val created = mutableListOf<MergeRequestCommand>()

        override fun verifyIdentity(): GitLabIdentity = GitLabIdentity("gudwns1812")

        override fun openMergeRequest(projectPath: String, sourceBranch: String): OpenMergeRequest? = existing

        override fun projectInfo(projectPath: String) = GitLabProjectInfo(default, projectTemplate)

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
        isPushed: Boolean? = true,
        commits: List<String> = listOf("S15P11A108-257 회원가입"),
        commitsOf: (String) -> List<String> = { commits },
        remoteBranches: List<String> = emptyList(),
        changedFiles: List<String> = emptyList(),
        templates: Map<String, String> = emptyMap(),
    ) = BranchState(branch, remoteUrl, isPushed, commitsOf, remoteBranches, { changedFiles }, templates)

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
    fun `does not block when it could not check whether the branch was pushed`() {
        // ls-remote는 네트워크나 SSH 키 문제로도 실패한다. 그것을 "안 올렸다"로 읽으면
        // 이미 푸시한 브랜치에 대고 먼저 푸시하라고 말하게 된다.
        assertTrue(useCase(FakeGitLab()).prepare(state(isPushed = null)) is MergeRequestPrepare.Ready)
    }

    @Test
    fun `reports a merge request that was created even without an address`() {
        val gitlab = object : MergeRequestPort by FakeGitLab() {
            override fun create(command: MergeRequestCommand) = CreatedMergeRequest(webUrl = null)
        }
        val proposal = readyOf(useCase(FakeGitLab()).prepare(state()))

        val result = useCase(gitlab).create(proposal, "제목", "본문")

        // 주소를 모르는 것과 만들지 못한 것은 다르다. 실패로 알리면 같은 MR을 또 만들려 든다.
        assertTrue(result is MergeRequestResult.Created)
        assertNull((result as MergeRequestResult.Created).webUrl)
    }

    @Test
    fun `offers the project template first, then Default, then the rest`() {
        val gitlab = FakeGitLab(projectTemplate = "## 프로젝트가 정한 양식")
        val state = state(
            templates = linkedMapOf("Bugfix" to "## 버그", "Default" to "## 기본", "Feature" to "## 기능"),
        )

        val proposal = readyOf(useCase(gitlab).prepare(state))

        // 프로젝트 설정이 저장소 Default.md보다 우선한다(GitLab 문서).
        assertEquals(
            listOf(CreateMergeRequestUseCase.PROJECT_TEMPLATE, "Default", "Bugfix", "Feature"),
            proposal.templates.keys.toList(),
        )
        assertEquals(CreateMergeRequestUseCase.PROJECT_TEMPLATE, proposal.templateName)
    }

    @Test
    fun `falls back to the repository Default when the project sets none`() {
        val state = state(templates = linkedMapOf("Bugfix" to "## 버그", "Default" to "## 기본"))

        assertEquals("Default", readyOf(useCase(FakeGitLab()).prepare(state)).templateName)
    }

    @Test
    fun `has no template to offer when the repository has none`() {
        val proposal = readyOf(useCase(FakeGitLab()).prepare(state()))

        assertTrue(proposal.templates.isEmpty())
        assertNull(proposal.templateName)
    }

    @Test
    fun `fills the chosen template instead of writing its own body`() {
        val state = state(
            templates = linkedMapOf(
                "Default" to "## 개요\n\n## 관련 이슈\n\n## 테스트",
                "Simple" to "## 요약",
            ),
        )
        val useCase = useCase(FakeGitLab(), listOf("S15P11A108-257"))
        val first = readyOf(useCase.prepare(state))

        assertTrue("템플릿의 절이 그대로 남는다", first.description.contains("## 테스트"))
        assertTrue(first.description.contains("- S15P11A108-257"))
        assertFalse("커밋 글은 넣지 않는다", first.description.contains("회원가입"))

        val other = useCase.redraft(state, first, templateName = "Simple")

        assertEquals("Simple", other.templateName)
        assertTrue("양식이 없으면 뒤에 붙인다", other.description.contains("## 관련 이슈"))
        assertTrue(other.description.startsWith("## 요약"))
    }

    @Test
    fun `writes its own body when the user picks no template`() {
        val state = state(templates = linkedMapOf("Default" to "## 개요"))
        val useCase = useCase(FakeGitLab(), listOf("S15P11A108-257"))
        val first = readyOf(useCase.prepare(state))

        val none = useCase.redraft(state, first, templateName = null)

        assertNull(none.templateName)
        assertTrue(none.description.startsWith("### 연결된 작업"))
        assertFalse(none.description.contains("## 개요"))
    }

    @Test
    fun `hands the chosen template to ai as well`() {
        var seen: DraftRequest? = null
        val state = state(templates = linkedMapOf("Default" to "## 개요\n\n## 관련 이슈"))
        val useCase = CreateMergeRequestUseCase(
            gitlab = FakeGitLab(),
            issueKeysOf = { listOf("S15P11A108-257") },
            draftWriter = DraftWriter { request -> seen = request; DraftOutcome.Failed("확인만 한다") },
        )

        useCase.rewrite(state, readyOf(useCase.prepare(state)))

        assertEquals("## 개요\n\n## 관련 이슈", seen?.template)
    }

    @Test
    fun `lets ai rewrite only the title and body`() {
        val written = WrittenDraft("S15P11A108-257 회원가입 화면", "### 연결된 작업\n- S15P11A108-257")
        val useCase = CreateMergeRequestUseCase(
            gitlab = FakeGitLab(),
            issueKeysOf = { listOf("S15P11A108-257") },
            draftWriter = DraftWriter { DraftOutcome.Written(written) },
        )
        val state = state()
        val before = readyOf(useCase.prepare(state))

        val after = (useCase.rewrite(state, before) as DraftRewrite.Rewritten).proposal

        assertEquals(written.title, after.title)
        assertEquals(written.description, after.description)
        // AI가 정할 일이 아닌 것은 그대로 둔다. 특히 이슈 키는 커밋에서 뽑은 사실이다(RULE.md 5.3).
        assertEquals(before.issueKeys, after.issueKeys)
        assertEquals(before.targetBranch, after.targetBranch)
        assertEquals(before.targetCandidates, after.targetCandidates)
        assertEquals(before.projectPath, after.projectPath)
    }

    @Test
    fun `sends the branch, keys, commits and file paths but no file contents`() {
        var seen: DraftRequest? = null
        val useCase = CreateMergeRequestUseCase(
            gitlab = FakeGitLab(),
            issueKeysOf = { listOf("S15P11A108-257") },
            draftWriter = DraftWriter { request -> seen = request; DraftOutcome.Failed("확인만 한다") },
        )
        val state = state(commits = listOf("S15P11A108-257 회원가입"), changedFiles = listOf("src/Signup.kt"))

        useCase.rewrite(state, readyOf(useCase.prepare(state)))

        assertEquals("feat/signup", seen?.sourceBranch)
        assertEquals(listOf("S15P11A108-257"), seen?.issueKeys)
        assertEquals(listOf("S15P11A108-257 회원가입"), seen?.commitSubjects)
        assertEquals(listOf("src/Signup.kt"), seen?.changedFiles)
    }

    @Test
    fun `refuses to rewrite when ai is not configured`() {
        val useCase = useCase(FakeGitLab())

        // 버튼은 늘 보인다. 꺼져 있으면 눌렀을 때 이 문구가 켜러 가라고 말해 준다.
        val refused = useCase.rewrite(state(), readyOf(useCase.prepare(state()))) as DraftRewrite.Refused
        assertTrue(refused.message.contains("꺼져"))
        assertTrue(refused.message.contains("설정"))
    }

    @Test
    fun `passes the reason through when ai cannot or will not write`() {
        fun useCaseOf(outcome: DraftOutcome) = CreateMergeRequestUseCase(
            gitlab = FakeGitLab(),
            issueKeysOf = { emptyList() },
            draftWriter = DraftWriter { outcome },
        )

        val missing = useCaseOf(DraftOutcome.Unavailable("Claude Code CLI를 찾을 수 없습니다."))
        val failed = useCaseOf(DraftOutcome.Failed("사용량 한도에 걸렸습니다."))
        val state = state()

        val a = missing.rewrite(state, readyOf(missing.prepare(state))) as DraftRewrite.Refused
        val b = failed.rewrite(state, readyOf(failed.prepare(state))) as DraftRewrite.Refused

        assertTrue("고칠 방법을 그대로 올린다", a.message.contains("찾을 수 없"))
        assertTrue(b.message.contains("한도"))
    }

    @Test
    fun `survives a writer that throws`() {
        val useCase = CreateMergeRequestUseCase(
            gitlab = FakeGitLab(),
            issueKeysOf = { emptyList() },
            draftWriter = DraftWriter { throw IllegalStateException("boom") },
        )
        val state = state()

        // AI가 터져도 MR 생성은 계속 갈 수 있어야 한다.
        assertTrue(useCase.rewrite(state, readyOf(useCase.prepare(state))) is DraftRewrite.Refused)
    }

    @Test
    fun `refuses a repository with no origin`() {
        assertTrue(blockedOf(useCase(FakeGitLab()).prepare(state(remoteUrl = null))).contains("remote"))
    }

    @Test
    fun `refuses a remote that belongs to a different host than the configured gitlab`() {
        // 토큰을 엉뚱한 서버로 보내지 않는다. 이 검사가 없으면 SSAFY 토큰이 github.com으로 날아간다.
        val useCase = CreateMergeRequestUseCase(
            gitlab = FakeGitLab(),
            issueKeysOf = { emptyList() },
            gitLabBaseUrl = "https://lab.ssafy.com",
        )

        val message = blockedOf(useCase.prepare(state(remoteUrl = "git@github.com:gudwns1812/repo.git")))

        assertTrue(message.contains("github.com"))
        assertTrue(message.contains("lab.ssafy.com"))
    }

    @Test
    fun `accepts a remote on the configured gitlab host`() {
        val useCase = CreateMergeRequestUseCase(
            gitlab = FakeGitLab(),
            issueKeysOf = { emptyList() },
            gitLabBaseUrl = "https://LAB.SSAFY.COM/",
        )

        // 대소문자와 끝 슬래시는 같은 곳을 가리킨다.
        assertTrue(useCase.prepare(state()) is MergeRequestPrepare.Ready)
    }

    @Test
    fun `does not compare hosts when no gitlab address is configured`() {
        val useCase = CreateMergeRequestUseCase(gitlab = FakeGitLab(), issueKeysOf = { emptyList() })

        assertTrue(useCase.prepare(state(remoteUrl = "git@github.com:a/b.git")) is MergeRequestPrepare.Ready)
    }

    @Test
    fun `separates an invalid token from insufficient permission and names the token source`() {
        val proposal = readyOf(useCase(FakeGitLab()).prepare(state()))
        fun useCaseOf(failure: Exception) = CreateMergeRequestUseCase(
            gitlab = FakeGitLab(failure = failure),
            issueKeysOf = { emptyList() },
            tokenOrigin = "GITLAB_TOKEN 환경 변수의 토큰",
        )

        val invalid = useCaseOf(GitLabAuthenticationError()).create(proposal, "제목", "본문")
        val forbidden = useCaseOf(GitLabPermissionError()).create(proposal, "제목", "본문")

        assertTrue((invalid as MergeRequestResult.Failure).message.contains("유효하지 않"))
        assertTrue("어느 토큰인지 밝힌다", invalid.message.contains("GITLAB_TOKEN"))
        assertTrue((forbidden as MergeRequestResult.Failure).message.contains("권한"))
        assertTrue("고칠 곳을 짚어 준다", forbidden.message.contains("api"))
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
    fun `separates a credential failure from a network failure`() {
        val proposal = readyOf(useCase(FakeGitLab()).prepare(state()))

        val denied = useCase(FakeGitLab(failure = GitLabAuthenticationError())).create(proposal, "제목", "본문")
        val offline = useCase(FakeGitLab(failure = GitLabNetworkError())).create(proposal, "제목", "본문")

        assertTrue((denied as MergeRequestResult.Failure).message.contains("토큰"))
        assertTrue((offline as MergeRequestResult.Failure).message.contains("통신"))
    }

    @Test
    fun `survives an error nobody planned for`() {
        val gitlab = FakeGitLab(failure = IllegalStateException("boom"))
        val proposal = readyOf(useCase(FakeGitLab()).prepare(state()))

        assertTrue(useCase(gitlab).create(proposal, "제목", "본문") is MergeRequestResult.Failure)
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
    fun `leaves the body without keys when no commit names one`() {
        // 아는 키가 없으면 비워 둔다. 저장소에 하나뿐인 현재 작업 번호로 추측하면
        // 브랜치를 갈아탄 뒤 엉뚱한 작업에 MR이 붙는다 — 안 붙는 것보다 나쁘다.
        val proposal = readyOf(useCase(FakeGitLab()).prepare(state(commitsOf = { emptyList() })))

        assertTrue(proposal.issueKeys.isEmpty())
        assertFalse(proposal.description.contains("연결된 작업"))
        assertTrue(proposal.title.isNotBlank())
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

        val proposal = readyOf(useCase(FakeGitLab(default = "develop")).prepare(state))

        assertEquals(listOf("develop", "main"), proposal.targetCandidates)
    }

    @Test
    fun `offers at least the default when the remote lists no branches`() {
        val proposal = readyOf(useCase(FakeGitLab(default = "develop")).prepare(state()))

        assertEquals(listOf("develop"), proposal.targetCandidates)
    }

    @Test
    fun `redraws the body from whichever target the user picks`() {
        // 통합 브랜치를 쓰는 팀에서 기본 브랜치를 기준으로 삼으면 남의 커밋이 딸려 온다.
        val perTarget = mapOf(
            "main" to listOf("S15P11A601-100 남의 커밋 1", "S15P11A601-101 남의 커밋 2", "S15P11A601-266 내 커밋"),
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
        assertTrue(picked.description.contains("S15P11A601-266"))
        assertEquals(listOf("S15P11A601-266"), picked.issueKeys)
    }

    @Test
    fun `writes the keys alone, not the commits that carried them`() {
        val state = state(commitsOf = { listOf("S15P11A601-266 내 작업", "빌드 고침", "오타 수정") })
        val useCase = CreateMergeRequestUseCase(
            gitlab = FakeGitLab(),
            issueKeysOf = { subjects -> subjects.flatMap { CommitIssueKeys.all(it) }.distinct() },
        )

        val proposal = readyOf(useCase.prepare(state))

        assertEquals(listOf("### 연결된 작업", "- S15P11A601-266"), proposal.description.lines())
        assertFalse("커밋 글은 본문에 넣지 않는다", proposal.description.contains("내 작업"))
        assertFalse(proposal.description.contains("빌드 고침"))
    }

    @Test
    fun `keeps what the target change cannot alter`() {
        val state = state(remoteBranches = listOf("main"))
        val useCase = useCase(FakeGitLab())
        val first = readyOf(useCase.prepare(state))

        val picked = useCase.redraft(state, first, "main")

        assertEquals(first.projectPath, picked.projectPath)
        assertEquals(first.sourceBranch, picked.sourceBranch)
        assertEquals(first.existingUrl, picked.existingUrl)
        assertEquals(first.targetCandidates, picked.targetCandidates)
    }

    @Test
    fun `redraws to an empty body when the picked target leaves no commits`() {
        val state = state(commitsOf = { target -> if (target == "main") listOf("S15P11A108-257 회원가입") else emptyList() })
        val useCase = useCase(FakeGitLab(default = "main"), listOf("S15P11A108-257"))
        val first = readyOf(useCase.prepare(state))

        val picked = useCase.redraft(state, first, "feat/other")

        assertTrue(first.issueKeys.isNotEmpty())
        assertTrue(picked.issueKeys.isEmpty())
        assertEquals("", picked.description)
    }

    @Test
    fun `still prepares when the commits cannot be read at all`() {
        val proposal = readyOf(
            useCase(FakeGitLab()).prepare(state(commitsOf = { throw IllegalStateException("git 없음") })),
        )

        assertEquals("feat/signup", proposal.sourceBranch)
        assertTrue(proposal.issueKeys.isEmpty())
    }
}
