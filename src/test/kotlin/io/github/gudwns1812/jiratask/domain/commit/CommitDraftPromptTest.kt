package io.github.gudwns1812.jiratask.domain.commit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI에게 **무엇을 보내는지**를 프로세스 없이 확인한다.
 *
 * diff까지 보내는 기능이라 MR 초안보다 이 확인이 중요하다. 여기서 통과하는 글이 곧
 * 사용자의 소스 코드 중 밖으로 나가는 전부다.
 */
class CommitDraftPromptTest {

    private fun request(
        issueKey: String? = "S15P11A108-257",
        branch: String? = "feature/signup",
        files: List<String> = listOf("src/Signup.kt"),
        diff: String? = null,
        hint: String? = null,
    ) = CommitDraftRequest(
        issueKey = issueKey,
        branch = branch,
        changedFiles = files,
        diff = diff,
        hint = hint,
    )

    @Test
    fun `carries the branch, the issue key and the changed files`() {
        val text = CommitDraftPrompt.of(request(files = listOf("src/Signup.kt", "src/SignupTest.kt")))

        assertTrue(text.contains("feature/signup"))
        assertTrue(text.contains("S15P11A108-257"))
        assertTrue(text.contains("- src/Signup.kt"))
        assertTrue(text.contains("- src/SignupTest.kt"))
    }

    @Test
    fun `tells the model not to write the issue key itself`() {
        // 키는 도구가 붙인다(RULE.md 5.2). 모델까지 쓰면 키가 두 번 들어간다.
        assertTrue(CommitDraftPrompt.SYSTEM.contains("Jira 이슈 키를 쓰지 않습니다"))
        assertTrue(CommitDraftPrompt.of(request()).contains("subject에는 쓰지 마세요"))
    }

    @Test
    fun `asks for one json object and nothing else`() {
        assertTrue(CommitDraftPrompt.SYSTEM.contains("""{"type": "...", "summary": "..."}"""))
        assertTrue(CommitDraftPrompt.SYSTEM.contains("코드펜스, 본문을 덧붙이지 않습니다"))
    }

    @Test
    fun `asks for a single line and nothing after it`() {
        // 팀의 커밋은 `키 + type: 한 줄`이 전부다(RULE.md 5.2). 본문이 오면 컨벤션이 깨진다.
        assertTrue(CommitDraftPrompt.SYSTEM.contains("출력에는 그 두 값뿐입니다"))
        assertTrue(CommitDraftPrompt.SYSTEM.contains("줄바꿈을 넣지 않습니다"))
        assertTrue(CommitDraftPrompt.SYSTEM.contains("마침표로 끝내지 않습니다"))
        assertFalse("본문을 쓸 자리가 있다고 알려서는 안 된다", CommitDraftPrompt.SYSTEM.contains("body"))
    }

    @Test
    fun `hands the model the team's prefix list, straight from the type`() {
        // 지시와 검사가 갈라지면 모델은 목록에 없는 말을 배우고, 그 답은 읽히지 못해 버려진다.
        CommitType.PREFIXES.forEach { prefix ->
            assertTrue("$prefix 이(가) 지시에 있어야 한다", CommitDraftPrompt.SYSTEM.contains(prefix))
        }
        assertTrue(CommitDraftPrompt.SYSTEM.contains("type은 다음 중 정확히 하나입니다"))
        assertTrue("접두사를 요약에 또 쓰지 않게 한다", CommitDraftPrompt.SYSTEM.contains("summary에는 type을 다시 쓰지 않습니다"))
    }

    @Test
    fun `sends the diff only when it is given`() {
        val without = CommitDraftPrompt.of(request(diff = null))
        assertFalse(without.contains("변경 내용(diff)"))

        val with = CommitDraftPrompt.of(request(diff = "--- a/src/Signup.kt\n+++ b/src/Signup.kt\n+val x = 1"))
        assertTrue(with.contains("변경 내용(diff)"))
        assertTrue(with.contains("+val x = 1"))
    }

    @Test
    fun `treats a blank diff as no diff at all`() {
        assertFalse(CommitDraftPrompt.of(request(diff = "   \n  ")).contains("변경 내용(diff)"))
    }

    @Test
    fun `says so when it clips a long diff`() {
        val long = (1..CommitDraftPrompt.DIFF_LINE_LIMIT + 10).joinToString("\n") { "+줄 $it" }

        val clipped = CommitDraftPrompt.clip(long)

        assertTrue(clipped.contains("+줄 1"))
        assertTrue(clipped.contains(CommitDraftPrompt.CLIPPED))
        assertFalse("잘린 뒤의 줄은 보내지 않는다", clipped.contains("+줄 ${CommitDraftPrompt.DIFF_LINE_LIMIT + 5}"))
    }

    @Test
    fun `clips a diff that is few lines but enormous`() {
        // 한 줄짜리 minified 파일이 흔하다. 줄 수만 세면 그런 파일이 통째로 나간다.
        val huge = "+" + "가".repeat(CommitDraftPrompt.DIFF_CHAR_LIMIT + 100)

        val clipped = CommitDraftPrompt.clip(huge)

        assertTrue(clipped.length < huge.length)
        assertTrue(clipped.contains(CommitDraftPrompt.CLIPPED))
    }

    @Test
    fun `keeps a short diff exactly as it is`() {
        val diff = "--- a/x\n+++ b/x\n+한 줄"

        assertEquals(diff, CommitDraftPrompt.clip(diff))
    }

    @Test
    fun `does not carry every file of a huge commit`() {
        val files = (1..CommitDraftPrompt.FILE_LIMIT + 5).map { "src/File$it.kt" }

        val text = CommitDraftPrompt.of(request(files = files))

        assertTrue(text.contains("- src/File1.kt"))
        assertTrue(text.contains("(외 5개 생략)"))
        assertFalse(text.contains("src/File${CommitDraftPrompt.FILE_LIMIT + 3}.kt"))
    }

    @Test
    fun `says there is nothing when no file changed`() {
        assertTrue(CommitDraftPrompt.of(request(files = emptyList())).contains("- (없음)"))
    }

    @Test
    fun `leaves out what it does not know`() {
        val text = CommitDraftPrompt.of(request(issueKey = null, branch = null, hint = null))

        assertFalse(text.contains("브랜치:"))
        assertFalse(text.contains("작업 키:"))
        assertFalse(text.contains("메모"))
    }

    @Test
    fun `passes the user's own words along as a hint`() {
        val text = CommitDraftPrompt.of(request(hint = "폼 검증 고침"))

        assertTrue(text.contains("폼 검증 고침"))
        assertTrue(text.contains("그대로 베끼지는 마세요"))
    }

    @Test
    fun `puts the instructions in front of the materials`() {
        val stdin = CommitDraftPrompt.stdinOf(request())

        assertTrue(stdin.startsWith(CommitDraftPrompt.SYSTEM))
        assertTrue(stdin.contains("feature/signup"))
    }
}
