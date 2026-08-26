package io.github.gudwns1812.jiratask.domain.mr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** remote URL 해석과, AI 없이 만드는 MR 제목·본문. */
class MergeRequestDraftTest {

    @Test
    fun `reads the project path out of an ssh remote`() {
        val remote = GitLabProject.of("git@lab.ssafy.com:s15-final/S15P11A108.git")

        assertEquals("lab.ssafy.com", remote?.host)
        assertEquals("s15-final/S15P11A108", remote?.projectPath)
    }

    @Test
    fun `reads the project path out of an https remote`() {
        val remote = GitLabProject.of("https://lab.ssafy.com/s15-final/S15P11A108.git")

        assertEquals("lab.ssafy.com", remote?.host)
        assertEquals("s15-final/S15P11A108", remote?.projectPath)
    }

    @Test
    fun `keeps every level of a nested group`() {
        assertEquals(
            "group/subgroup/project",
            GitLabProject.of("git@lab.ssafy.com:group/subgroup/project.git")?.projectPath,
        )
    }

    @Test
    fun `copes with a remote that has no dot git, a port, or a trailing slash`() {
        assertEquals("a/b", GitLabProject.of("git@host:a/b")?.projectPath)
        assertEquals("a/b", GitLabProject.of("https://host:8443/a/b/")?.projectPath)
        assertEquals("a/b", GitLabProject.of("ssh://git@host/a/b.git")?.projectPath)
    }

    @Test
    fun `drops the user name in front of an https remote`() {
        assertEquals("lab.ssafy.com", GitLabProject.of("https://oauth2:token@lab.ssafy.com/a/b.git")?.host)
    }

    @Test
    fun `has no project to offer for something that is not a remote`() {
        assertNull(GitLabProject.of(null))
        assertNull(GitLabProject.of(""))
        assertNull(GitLabProject.of("   "))
        assertNull(GitLabProject.of("not a url"))
    }

    @Test
    fun `has no project to offer when the path names no group`() {
        // `group/name`이 최소 형태다.
        assertNull(GitLabProject.of("git@host:project.git"))
    }

    @Test
    fun `tells whether the remote belongs to the configured gitlab`() {
        val remote = GitLabProject.of("git@lab.ssafy.com:a/b.git")!!

        assertTrue(remote.matches("https://lab.ssafy.com"))
        assertTrue(remote.matches("https://lab.ssafy.com/"))
        assertTrue(remote.matches("https://LAB.SSAFY.COM"))
        assertTrue("포트는 견준다", remote.matches("https://lab.ssafy.com:8443"))
        assertTrue(!remote.matches("https://gitlab.com"))
        assertTrue(!remote.matches(null))
        assertTrue(!remote.matches(""))
    }

    private fun summaryOf(key: String): String? = when (key) {
        "S15P11A108-257" -> "[AUTH-001 | FE] 회원가입 화면 구현"
        else -> null
    }

    @Test
    fun `titles a single-issue merge request with that issue`() {
        val title = MergeRequestDraft.titleOf("feat/signup", listOf("S15P11A108-257"), ::summaryOf)

        assertEquals("S15P11A108-257 [AUTH-001 | FE] 회원가입 화면 구현", title)
    }

    @Test
    fun `falls back to the key alone when the summary is unknown`() {
        assertEquals("S15P11A108-999", MergeRequestDraft.titleOf("feat/x", listOf("S15P11A108-999"), ::summaryOf))
    }

    @Test
    fun `titles a multi-issue merge request from the branch name`() {
        // 브랜치 이름에는 이제 요약이 들어 있다(RULE.md 5.1).
        val title = MergeRequestDraft.titleOf("feat/live-chat", listOf("A-1", "A-2"), ::summaryOf)

        assertEquals("live chat", title)
    }

    @Test
    fun `titles from the branch when the commits name no issue`() {
        assertEquals("token refresh", MergeRequestDraft.titleOf("fix/token_refresh", emptyList(), ::summaryOf))
    }

    @Test
    fun `has a title to offer even without a branch name`() {
        assertEquals(MergeRequestDraft.FALLBACK_TITLE, MergeRequestDraft.titleOf(null, emptyList(), ::summaryOf))
        assertEquals(MergeRequestDraft.FALLBACK_TITLE, MergeRequestDraft.titleOf("feat/", emptyList(), ::summaryOf))
    }

    @Test
    fun `lists the linked issues and the commits in the description`() {
        val description = MergeRequestDraft.descriptionOf(
            listOf("S15P11A108-257"),
            listOf(BranchCommit("S15P11A108-257 회원가입 화면"), BranchCommit("S15P11A108-257 검증 추가")),
            "https://team.atlassian.net",
        )

        assertTrue(description.contains("### 연결된 작업"))
        assertTrue(
            "Jira가 MR을 작업에 붙일 수 있도록 링크로 적는다",
            description.contains("[S15P11A108-257](https://team.atlassian.net/browse/S15P11A108-257)"),
        )
        assertTrue(description.contains("### 커밋"))
        assertTrue(description.contains("- S15P11A108-257 검증 추가"))
    }

    @Test
    fun `writes the bare key when no jira address is configured`() {
        val description = MergeRequestDraft.descriptionOf(listOf("A-1"), emptyList(), null)

        assertTrue(description.contains("- A-1"))
        assertTrue(!description.contains("]("))
    }

    @Test
    fun `leaves out a section that would be empty`() {
        assertEquals("", MergeRequestDraft.descriptionOf(emptyList(), emptyList(), null))
        assertTrue(!MergeRequestDraft.descriptionOf(emptyList(), listOf(BranchCommit("첫 커밋")), null).contains("연결된 작업"))
        assertTrue(!MergeRequestDraft.descriptionOf(listOf("A-1"), emptyList(), null).contains("커밋"))
    }
}
