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
        "S15P11A108-258" -> "[INFRA | BE] CI/CD 파이프라인 구축"
        else -> null
    }

    @Test
    fun `titles a single-issue merge request with that issue's own title`() {
        val title = MergeRequestDraft.titleOf("feat/signup", listOf("S15P11A108-257"), ::summaryOf)

        // 이슈 키는 넣지 않는다 — Jira를 잇는 것은 본문의 키이고, 제목은 사람이 읽는 자리다.
        // 기능 ID(AUTH-001)도 뺀 태그로 다시 붙인다.
        assertEquals("[FE] 회원가입 화면 구현", title)
    }

    @Test
    fun `falls back to the branch when the summary is unknown`() {
        assertEquals("x", MergeRequestDraft.titleOf("feat/x", listOf("S15P11A108-999"), ::summaryOf))
    }

    @Test
    fun `keeps the area tag on a multi-issue merge request`() {
        // 브랜치 이름에는 이제 요약이 들어 있다(RULE.md 5.1). 태그는 작업에서 가져온다.
        val title = MergeRequestDraft.titleOf(
            "feat/live-chat",
            listOf("S15P11A108-258", "S15P11A108-257"),
            ::summaryOf,
        )

        assertEquals("[INFRA | BE] live chat", title)
    }

    @Test
    fun `reads the tag out of a jira task title`() {
        assertEquals("[INFRA | BE]", MergeRequestDraft.tagOf("[INFRA | BE] CI/CD 파이프라인 구축"))
        assertEquals("[PLAN]", MergeRequestDraft.tagOf("  [PLAN] 서비스 목표 정의  "))
        assertEquals("[DESIGN | FE]", MergeRequestDraft.tagOf("[DESIGN | FE] 와이어프레임 제작"))
    }

    @Test
    fun `leaves the feature id out of the tag`() {
        // AUTH-001은 Jira 안에서 스토리를 가리키는 번호다. MR 목록에서는 아무것도 알려주지 않는다.
        assertEquals("[FE]", MergeRequestDraft.tagOf("[AUTH-001 | FE] 이메일 회원가입 API 구현"))
        assertEquals("[BE]", MergeRequestDraft.tagOf("[S15P21A403-19 | BE] 방 입장 인증"))
        // 영역이 안 적혔으면 번호만 떼고 갈래 이름은 남긴다.
        assertEquals("[AUTH]", MergeRequestDraft.tagOf("[AUTH-001] 회원가입"))
        assertNull("남길 이름이 아예 없으면 태그도 없다", MergeRequestDraft.tagOf("[ | ] 무엇"))
    }

    @Test
    fun `has no tag to offer when the task title carries none`() {
        // 태그는 읽어 오기만 한다. 잘못 붙은 영역은 아무도 고쳐 주지 않는다.
        assertNull(MergeRequestDraft.tagOf("회원가입 화면 구현"))
        assertNull(MergeRequestDraft.tagOf("[] 빈 태그"))
        assertNull(MergeRequestDraft.tagOf("[닫히지 않은 태그"))
        assertNull(MergeRequestDraft.tagOf(null))
    }

    @Test
    fun `puts the tag in front and never twice`() {
        assertEquals("[BE] 로그인 API 구현", MergeRequestDraft.withTag("[BE]", "로그인 API 구현"))
        assertEquals(
            "AI가 흉내 낸 태그는 걷어낸다",
            "[BE] 로그인 API 구현",
            MergeRequestDraft.withTag("[BE]", "[FE] 로그인 API 구현"),
        )
        assertEquals(
            "AI가 붙인 이슈 키도 걷어낸다",
            "[BE] 로그인 API 구현",
            MergeRequestDraft.withTag("[BE]", "S15P11A108-257 로그인 API 구현"),
        )
        assertEquals("태그가 없으면 제목만 남는다", "로그인 API 구현", MergeRequestDraft.withTag(null, "[FE] 로그인 API 구현"))
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
