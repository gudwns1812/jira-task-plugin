package io.github.gudwns1812.jiratask.ui.commit

import io.github.gudwns1812.jiratask.domain.jira.JiraStatusCategory
import io.github.gudwns1812.jiratask.domain.jira.SprintTask
import io.github.gudwns1812.jiratask.ui.task.TaskAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RULE.md 5.2의 세 가지 확인. 무엇에 경고하지 *않는가*가 절반이다. */
class CommitGuardTest {

    private fun task(key: String, inSprint: Boolean = true, done: Boolean = false) = TaskAnswer.Known(
        SprintTask(
            key = key,
            summary = "제목",
            statusName = "상태",
            statusCategory = if (done) JiraStatusCategory.DONE else JiraStatusCategory.TO_DO,
            inOpenSprint = inSprint,
        ),
    )

    private fun inspect(message: String?, answer: TaskAnswer = task("S15P11A108-257")) =
        CommitGuard.inspect(message) { answer }

    private fun reasonOf(verdict: CommitGuardVerdict): CommitGuardReason? =
        (verdict as? CommitGuardVerdict.Warn)?.reason

    @Test
    fun `passes a commit that starts with a task in the sprint`() {
        assertEquals(CommitGuardVerdict.Ok, inspect("S15P11A108-257 회원가입 화면 구현"))
    }

    @Test
    fun `warns when the message carries no issue key`() {
        val verdict = inspect("회원가입 화면 구현")

        assertEquals(CommitGuardReason.NO_ISSUE_KEY, reasonOf(verdict))
        assertEquals(CommitGuard.NO_ISSUE_KEY, (verdict as CommitGuardVerdict.Warn).message)
    }

    @Test
    fun `warns when the key sits in the middle instead of the front`() {
        // RULE.md 5.2는 메시지가 키로 *시작*하도록 정한다. automation이 앞을 본다.
        assertEquals(CommitGuardReason.NO_ISSUE_KEY, reasonOf(inspect("회원가입 구현 S15P11A108-257")))
    }

    @Test
    fun `warns about a key jira does not know`() {
        val verdict = inspect("S15P11A108-9999 오타", TaskAnswer.NotFound)

        assertEquals(CommitGuardReason.UNKNOWN_ISSUE_KEY, reasonOf(verdict))
        assertTrue((verdict as CommitGuardVerdict.Warn).message.contains("S15P11A108-9999"))
    }

    @Test
    fun `warns about a task that is not in the current sprint`() {
        val verdict = inspect("S15P11A108-9 지난 스프린트", task("S15P11A108-9", inSprint = false))

        assertEquals(CommitGuardReason.OUTSIDE_SPRINT, reasonOf(verdict))
        assertTrue((verdict as CommitGuardVerdict.Warn).message.contains("현재 스프린트"))
    }

    @Test
    fun `says nothing when the lookup could not be answered`() {
        // 확인할 수 없는 것과 위반한 것은 다르다. 오프라인에서 거짓 경고를 내지 않는다.
        assertEquals(CommitGuardVerdict.Ok, inspect("S15P11A108-257 구현", TaskAnswer.Unanswered))
    }

    @Test
    fun `says nothing about committing to a finished task`() {
        // 리뷰 지적 반영, 후속 수정. 규칙 위반이 아니다(RULE.md 4.3).
        val verdict = inspect("S15P11A108-9 후속 수정", task("S15P11A108-9", done = true))

        assertEquals(CommitGuardVerdict.Ok, verdict)
    }

    @Test
    fun `accepts a teammates key as long as it is in the sprint`() {
        // 캐시는 내 작업만 담지만, 되물어 스프린트 안이면 경고하지 않는다.
        assertEquals(CommitGuardVerdict.Ok, inspect("S15P11A108-999 대신 처리", task("S15P11A108-999")))
    }

    @Test
    fun `treats an empty or blank message as having no key`() {
        assertEquals(CommitGuardReason.NO_ISSUE_KEY, reasonOf(inspect("")))
        assertEquals(CommitGuardReason.NO_ISSUE_KEY, reasonOf(inspect("   \n  ")))
        assertEquals(CommitGuardReason.NO_ISSUE_KEY, reasonOf(inspect(null)))
    }

    @Test
    fun `checks the leading key even when the message names several`() {
        var asked: String? = null
        CommitGuard.inspect("S15P11A108-1 S15P11A108-2 함께") { key ->
            asked = key
            task(key)
        }

        assertEquals("맨 앞의 키를 본다", "S15P11A108-1", asked)
    }

    @Test
    fun `ignores leading whitespace before the key`() {
        assertEquals(CommitGuardVerdict.Ok, inspect("   S15P11A108-257 구현"))
    }

    @Test
    fun `does not mistake a lowercase word for a key`() {
        assertEquals(CommitGuardReason.NO_ISSUE_KEY, reasonOf(inspect("abc-123 구현")))
    }

    @Test
    fun `finds every key in a message and drops the duplicates`() {
        assertEquals(
            listOf("S15P11A108-1", "S15P11A108-2"),
            CommitIssueKeys.all("S15P11A108-1 그리고 S15P11A108-2 그리고 다시 S15P11A108-1"),
        )
    }

    @Test
    fun `has no leading key to offer for a message that starts with text`() {
        assertNull(CommitIssueKeys.leading("구현 S15P11A108-1"))
        assertNull(CommitIssueKeys.leading(null))
    }
}
