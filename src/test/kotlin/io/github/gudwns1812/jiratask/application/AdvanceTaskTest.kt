package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.jira.JiraAuthenticationError
import io.github.gudwns1812.jiratask.domain.jira.JiraIssue
import io.github.gudwns1812.jiratask.domain.jira.JiraNetworkError
import io.github.gudwns1812.jiratask.domain.jira.JiraRequestError
import io.github.gudwns1812.jiratask.domain.jira.JiraStatusCategory
import io.github.gudwns1812.jiratask.domain.jira.JiraTransition
import io.github.gudwns1812.jiratask.domain.jira.TaskIssueTransitioner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 작업 이슈를 `진행 중`으로 옮기는 규칙.
 *
 * 어느 상태가 진행 중인지 **묻지 않고 카테고리로 고른다**는 것이 핵심이다.
 */
class AdvanceTaskTest {

    private class FakeTransitioner(
        private val issue: JiraIssue? = TODO_TASK,
        private val transitions: List<JiraTransition> = listOf(TO_IN_PROGRESS),
        private val issueFailure: Exception? = null,
        private val transitionsFailure: Exception? = null,
        private val transitionFailure: Exception? = null,
    ) : TaskIssueTransitioner {

        val executed = mutableListOf<Pair<String, String>>()
        var listedTransitions: Int = 0
            private set

        override fun getIssue(issueKey: String): JiraIssue {
            issueFailure?.let { throw it }
            return issue!!
        }

        override fun listTransitions(issueKey: String): List<JiraTransition> {
            listedTransitions++
            transitionsFailure?.let { throw it }
            return transitions
        }

        override fun transition(issueKey: String, transitionId: String) {
            transitionFailure?.let { throw it }
            executed += issueKey to transitionId
        }
    }

    private fun command() = AdvanceTaskCommand(taskKey = "S15P11A108-257", taskIssueTypeId = "10002")

    private fun advance(transitioner: FakeTransitioner) = AdvanceTaskUseCase(transitioner).execute(command())

    @Test
    fun `moves the task through the transition whose target is in progress`() {
        val transitioner = FakeTransitioner()

        val result = advance(transitioner)

        assertEquals(AdvanceTaskResult.Success("S15P11A108-257", "진행 중"), result)
        assertEquals(listOf("S15P11A108-257" to "5"), transitioner.executed)
    }

    @Test
    fun `picks by category, not by the status name`() {
        // 상태 이름은 사이트 언어와 워크플로마다 다르다. 카테고리는 Jira가 보장한다.
        val transitioner = FakeTransitioner(
            transitions = listOf(JiraTransition("7", "999", "Doing", JiraStatusCategory.IN_PROGRESS)),
        )

        val result = advance(transitioner)

        assertEquals(AdvanceTaskResult.Success("S15P11A108-257", "Doing"), result)
        assertEquals(listOf("S15P11A108-257" to "7"), transitioner.executed)
    }

    @Test
    fun `never moves the task to a done or to-do status`() {
        val transitioner = FakeTransitioner(
            transitions = listOf(
                JiraTransition("1", "11", "해야 할 일", JiraStatusCategory.TO_DO),
                JiraTransition("9", "31", "완료", JiraStatusCategory.DONE),
            ),
        )

        val failure = advance(transitioner) as AdvanceTaskResult.Failure

        assertEquals(1, failure.exitCode)
        assertTrue(failure.message.contains("'진행 중' 단계로 가는 전환이 없습니다"))
        assertTrue("현재 상태를 알려 준다", failure.message.contains("해야 할 일"))
        assertTrue(transitioner.executed.isEmpty())
    }

    @Test
    fun `does nothing when the task is already in progress`() {
        // 플러그인과 automation이 같은 전환을 겹쳐 실행한다(RULE.md 4.3).
        val transitioner = FakeTransitioner(
            issue = TODO_TASK.copy(
                statusId = "21",
                statusName = "진행 중",
                statusCategory = JiraStatusCategory.IN_PROGRESS,
            ),
        )

        val result = advance(transitioner)

        assertEquals(AdvanceTaskResult.AlreadyThere("S15P11A108-257", "진행 중"), result)
        assertEquals("이미 진행 중이면 전환 목록도 읽지 않는다", 0, transitioner.listedTransitions)
    }

    @Test
    fun `never moves a finished task back`() {
        // 끝난 작업의 상태는 어떤 경우에도 바꾸지 않는다(RULE.md 4.3). 화면에서도 막지만
        // 번호를 직접 친 경우에는 여기가 유일한 방벽이다.
        val transitioner = FakeTransitioner(
            issue = TODO_TASK.copy(statusName = "완료", statusCategory = JiraStatusCategory.DONE),
        )

        val result = advance(transitioner)

        assertEquals(AdvanceTaskResult.LeftAlone("S15P11A108-257", "완료"), result)
        assertEquals("전환 목록조차 읽지 않는다", 0, transitioner.listedTransitions)
        assertEquals("전환을 부르지 않는다", 0, transitioner.executed.size)
    }

    @Test
    fun `treats an unknown category as not yet started`() {
        val transitioner = FakeTransitioner(
            issue = TODO_TASK.copy(statusCategory = JiraStatusCategory.UNKNOWN),
        )

        assertTrue(advance(transitioner) is AdvanceTaskResult.Success)
    }

    @Test
    fun `refuses an issue that is not a task`() {
        val transitioner = FakeTransitioner(
            issue = TODO_TASK.copy(issueTypeId = "10001", issueTypeName = "스토리"),
        )

        val failure = advance(transitioner) as AdvanceTaskResult.Failure

        assertEquals(1, failure.exitCode)
        assertTrue(failure.message.contains("작업 타입이 아닙니다"))
        assertTrue("잘못된 타입의 이슈는 건드리지 않는다", transitioner.executed.isEmpty())
    }

    @Test
    fun `reports an empty transition list`() {
        assertEquals(1, exitCodeOf(FakeTransitioner(transitions = emptyList())))
    }

    @Test
    fun `maps jira failures while reading the issue`() {
        assertEquals(3, exitCodeOf(FakeTransitioner(issueFailure = JiraAuthenticationError())))
        assertEquals(4, exitCodeOf(FakeTransitioner(issueFailure = JiraNetworkError())))
        assertEquals(1, exitCodeOf(FakeTransitioner(issueFailure = JiraRequestError("이슈를 찾을 수 없습니다."))))
        assertEquals(4, exitCodeOf(FakeTransitioner(issueFailure = IllegalStateException("알 수 없음"))))
    }

    @Test
    fun `maps a failure while reading the transitions`() {
        assertEquals(4, exitCodeOf(FakeTransitioner(transitionsFailure = JiraNetworkError())))
    }

    @Test
    fun `maps a failure while moving the task`() {
        val transitioner = FakeTransitioner(transitionFailure = JiraAuthenticationError())

        val failure = advance(transitioner) as AdvanceTaskResult.Failure

        assertEquals(3, failure.exitCode)
        assertTrue(transitioner.executed.isEmpty())
    }

    private fun exitCodeOf(transitioner: FakeTransitioner): Int =
        (advance(transitioner) as AdvanceTaskResult.Failure).exitCode

    private companion object {
        val TODO_TASK = JiraIssue(
            key = "S15P11A108-257",
            issueTypeId = "10002",
            issueTypeName = "작업",
            statusId = "11",
            statusName = "해야 할 일",
            summary = "[AUTH-001 | FE] 이메일 회원가입 화면 구현",
            statusCategory = JiraStatusCategory.TO_DO,
        )
        val TO_IN_PROGRESS = JiraTransition("5", "21", "진행 중", JiraStatusCategory.IN_PROGRESS)
    }
}
