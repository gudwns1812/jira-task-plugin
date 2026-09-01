package io.github.gudwns1812.jiratask.domain.mr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI에게 정확히 무엇을 보내는가. CLI를 부르지 않고 확인할 수 있어야 하는 부분이다. */
class ClaudeDraftPromptTest {

    private fun request(
        commits: List<String> = listOf("S15P11A601-266 docs/edit-build.gradle"),
        files: List<String> = listOf("build.gradle"),
        keys: List<String> = listOf("S15P11A601-266"),
    ) = DraftRequest(
        sourceBranch = "feat/signup",
        targetBranch = "backend-dev",
        issueKeys = keys,
        commitSubjects = commits,
        changedFiles = files,
    )

    @Test
    fun `carries the branches, keys, commits and file paths`() {
        val prompt = ClaudeDraftPrompt.of(request())

        assertTrue(prompt.contains("feat/signup"))
        assertTrue(prompt.contains("backend-dev"))
        assertTrue(prompt.contains("S15P11A601-266"))
        assertTrue(prompt.contains("docs/edit-build.gradle"))
        assertTrue(prompt.contains("build.gradle"))
    }

    @Test
    fun `never carries file contents`() {
        // 이 테스트가 깨지면 diff를 싣기 시작했다는 뜻이다. 그건 설계 결정이지 실수여서는 안 된다.
        val prompt = ClaudeDraftPrompt.of(request(files = listOf("src/Secret.kt")))

        assertTrue(prompt.contains("src/Secret.kt"))
        assertTrue("경로만 보낸다고 밝힌다", prompt.contains("내용은 제공하지 않습니다"))
    }

    @Test
    fun `caps how many commits and files it sends`() {
        val many = (1..120).map { "커밋 $it" }
        val prompt = ClaudeDraftPrompt.of(request(commits = many, files = many))

        assertEquals(ClaudeDraftPrompt.LIMIT, prompt.lines().count { it == "- 커밋 1" || it.startsWith("- 커밋 ") } / 2)
        assertTrue("생략했다고 밝힌다", prompt.contains("외 ${many.size - ClaudeDraftPrompt.LIMIT}개 생략"))
    }

    @Test
    fun `says there is nothing when a section is empty`() {
        val prompt = ClaudeDraftPrompt.of(request(commits = emptyList(), files = emptyList(), keys = emptyList()))

        assertTrue(prompt.contains("(없음"))
        assertTrue("키가 없으면 절을 만들지 말라고 한다", prompt.contains("작업 키 절을 만들지 마세요"))
    }

    @Test
    fun `drops blank entries instead of sending empty bullets`() {
        val prompt = ClaudeDraftPrompt.of(request(commits = listOf("  ", "진짜 커밋", "")))

        assertTrue(prompt.contains("- 진짜 커밋"))
        assertFalse(prompt.contains("- \n"))
    }

    @Test
    fun `tells the model to keep the issue keys`() {
        // 본문의 키 문자열이 Jira가 MR을 작업에 붙이는 근거다(RULE.md 5.3). AI가 지우면 기능이 망가진다.
        assertTrue(ClaudeDraftPrompt.SYSTEM.contains("### 연결된 작업"))
        assertTrue(ClaudeDraftPrompt.SYSTEM.contains("빠뜨리지 않습니다"))
        assertTrue("JSON만 내라고 한다", ClaudeDraftPrompt.SYSTEM.contains("JSON"))
    }
}
