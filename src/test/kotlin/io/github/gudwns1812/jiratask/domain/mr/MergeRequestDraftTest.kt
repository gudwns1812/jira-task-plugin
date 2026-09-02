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
        "S15P11A108-301" -> "[INFRA | BE] CI/CD 파이프라인 구축"
        "S15P11A108-302" -> "긴급 핫픽스"
        else -> null
    }

    @Test
    fun `titles a single-issue merge request with that issue`() {
        val title = MergeRequestDraft.titleOf("feat/signup", listOf("S15P11A108-257"), ::summaryOf)

        assertEquals("[AUTH] 회원가입 화면 구현", title)
    }

    @Test
    fun `keeps the issue key out of the title`() {
        // 키는 본문 `### 연결된 작업`에 있다(RULE.md 5.3). 제목에까지 두면 읽을 자리만 좁아진다.
        val title = MergeRequestDraft.titleOf("feat/signup", listOf("S15P11A108-257"), ::summaryOf)

        assertTrue("제목에 작업 키가 없다", !title.contains("S15P11A108"))
    }

    @Test
    fun `cuts the issue title down to its domain code`() {
        // 일련번호와 담당 영역은 뗀다(RULE.md 1.3).
        val title = MergeRequestDraft.titleOf("feat/pipeline", listOf("S15P11A108-301"), ::summaryOf)

        assertEquals("[INFRA] CI/CD 파이프라인 구축", title)
    }

    @Test
    fun `leaves an issue title that follows no such rule alone`() {
        val title = MergeRequestDraft.titleOf("fix/hotfix", listOf("S15P11A108-302"), ::summaryOf)

        assertEquals("긴급 핫픽스", title)
    }

    @Test
    fun `falls back to the branch name when the summary is unknown`() {
        // 키 하나만 적힌 제목은 읽을 말이 없다. 브랜치 이름이 그보다 낫다.
        val title = MergeRequestDraft.titleOf("fix/token-refresh", listOf("S15P11A108-999"), ::summaryOf)

        assertEquals("token refresh", title)
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
    fun `lists the issue keys under one section`() {
        val description = MergeRequestDraft.descriptionOf(listOf("S15P11A108-257", "S15P11A108-258"))

        assertEquals(
            """
            ### 연결된 작업
            - S15P11A108-257
            - S15P11A108-258
            """.trimIndent(),
            description,
        )
    }

    @Test
    fun `writes the key alone, without the commit that carried it`() {
        // 읽는 사람이 여기서 찾는 것은 "어느 작업인가" 하나뿐이다. 무슨 일을 했는지는 커밋 목록이 보여 준다.
        val description = MergeRequestDraft.descriptionOf(listOf("A-1"))

        assertEquals(listOf("### 연결된 작업", "- A-1"), description.lines())
        assertTrue("주소를 붙이지 않는다", !description.contains("](") && !description.contains("http"))
    }

    @Test
    fun `keeps no separate commit section`() {
        val description = MergeRequestDraft.descriptionOf(listOf("A-1"))

        assertTrue(!description.contains("### 커밋"))
        assertEquals(1, description.lines().count { it.startsWith("###") })
    }

    @Test
    fun `says the same key only once`() {
        val description = MergeRequestDraft.descriptionOf(listOf("A-1", "A-1"))

        assertEquals(1, description.lines().count { it == "- A-1" })
    }

    @Test
    fun `has nothing to write when no commit named a task`() {
        assertEquals("", MergeRequestDraft.descriptionOf(emptyList()))
        assertEquals("", MergeRequestDraft.descriptionOf(listOf("   ")))
    }

    @Test
    fun `fills a template with the keys instead of writing its own section`() {
        val description = MergeRequestDraft.descriptionOf(
            listOf("A-1"),
            template = """
                ## 개요

                ## 관련 이슈

                ## 테스트
            """.trimIndent(),
        )

        assertTrue(description.contains("## 개요"))
        assertTrue(description.lines()[3] == "- A-1")
        assertTrue("템플릿을 쓰면 우리 절 제목은 만들지 않는다", !description.contains("### 연결된 작업"))
    }
}
