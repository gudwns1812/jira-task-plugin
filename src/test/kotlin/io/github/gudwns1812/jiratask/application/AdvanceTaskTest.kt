package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.jira.JiraAuthenticationError
import io.github.gudwns1812.jiratask.domain.jira.JiraIssue
import io.github.gudwns1812.jiratask.domain.jira.JiraNetworkError
import io.github.gudwns1812.jiratask.domain.jira.JiraRequestError
import io.github.gudwns1812.jiratask.domain.jira.JiraTransition
import io.github.gudwns1812.jiratask.domain.jira.TaskIssueTransitioner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 작업 이슈를 진행 중·완료로 옮기는 규칙. */
class AdvanceTaskTest {

    private class FakeTransitioner(
        private val issue: JiraIssue? = TASK_ISSUE,
        private val transitions: List<JiraTransition> = listOf(TO_DONE),
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

    private fun command(statusId: String = "31", statusName: String = "완료") = AdvanceTaskCommand(
        taskKey = "S15P11A108-257",
        taskIssueTypeId = "10002",
        targetStatusId = statusId,
        targetStatusName = statusName,
    )

    @Test
    fun `moves the task with the transition that targets the status id`() {
        val transitioner = FakeTransitioner()

        val result = AdvanceTaskUseCase(transitioner).execute(command())

        assertEquals(AdvanceTaskResult.Success("S15P11A108-257", "완료"), result)
        assertEquals(listOf("S15P11A108-257" to "5"), transitioner.executed)
    }

    @Test
    fun `falls back to the status name when the id is not in this workflow`() {
        // 작업 워크플로의 완료 상태 ID가 스토리 쪽과 다른 경우. 이름이 같으면 찾아낸다.
        val transitioner = FakeTransitioner(transitions = listOf(JiraTransition("7", "999", "완료")))

        val result = AdvanceTaskUseCase(transitioner).execute(command())

        assertEquals(AdvanceTaskResult.Success("S15P11A108-257", "완료"), result)
        assertEquals(listOf("S15P11A108-257" to "7"), transitioner.executed)
    }

    @Test
    fun `matches the status name regardless of case`() {
        val transitioner = FakeTransitioner(transitions = listOf(JiraTransition("7", "999", "In Progress")))

        // 목표 ID(41)는 이 워크플로에 없고, 현재 상태(21)와도 다르다.
        val result = AdvanceTaskUseCase(transitioner).execute(command("41", "in progress"))

        assertEquals(listOf("S15P11A108-257" to "7"), transitioner.executed)
        assertTrue(result is AdvanceTaskResult.Success)
    }

    @Test
    fun `does nothing when the task already has the target status id`() {
        val transitioner = FakeTransitioner(issue = TASK_ISSUE.copy(statusId = "31", statusName = "완료"))

        val result = AdvanceTaskUseCase(transitioner).execute(command())

        assertEquals(AdvanceTaskResult.AlreadyThere("S15P11A108-257", "완료"), result)
        assertEquals("이미 목표 상태면 전환 목록도 읽지 않는다", 0, transitioner.listedTransitions)
    }

    @Test
    fun `does nothing when the task already has the target status name`() {
        val transitioner = FakeTransitioner(issue = TASK_ISSUE.copy(statusId = "999", statusName = "완료"))

        val result = AdvanceTaskUseCase(transitioner).execute(command())

        assertEquals(AdvanceTaskResult.AlreadyThere("S15P11A108-257", "완료"), result)
    }

    @Test
    fun `refuses an issue that is not a task`() {
        val transitioner = FakeTransitioner(
            issue = TASK_ISSUE.copy(issueTypeId = "10001", issueTypeName = "스토리"),
        )

        val result = AdvanceTaskUseCase(transitioner).execute(command())

        val failure = result as AdvanceTaskResult.Failure
        assertEquals(1, failure.exitCode)
        assertTrue(failure.message.contains("작업 타입이 아닙니다"))
        assertTrue("잘못된 타입의 이슈는 건드리지 않는다", transitioner.executed.isEmpty())
    }

    @Test
    fun `reports a workflow without the target status`() {
        val transitioner = FakeTransitioner(transitions = listOf(JiraTransition("9", "41", "검토 중")))

        val failure = AdvanceTaskUseCase(transitioner).execute(command()) as AdvanceTaskResult.Failure

        assertEquals(1, failure.exitCode)
        assertTrue(failure.message.contains("'완료' 상태로 전환할 수 있는"))
        assertTrue("현재 상태를 알려 준다", failure.message.contains("진행 중"))
        assertTrue(transitioner.executed.isEmpty())
    }

    @Test
    fun `reports an empty transition list`() {
        val failure = AdvanceTaskUseCase(FakeTransitioner(transitions = emptyList()))
            .execute(command()) as AdvanceTaskResult.Failure

        assertEquals(1, failure.exitCode)
    }

    @Test
    fun `maps jira failures while reading the issue`() {
        assertEquals(
            3,
            exitCodeOf(FakeTransitioner(issueFailure = JiraAuthenticationError())),
        )
        assertEquals(
            4,
            exitCodeOf(FakeTransitioner(issueFailure = JiraNetworkError())),
        )
        assertEquals(
            1,
            exitCodeOf(FakeTransitioner(issueFailure = JiraRequestError("이슈를 찾을 수 없습니다."))),
        )
        assertEquals(
            4,
            exitCodeOf(FakeTransitioner(issueFailure = IllegalStateException("알 수 없음"))),
        )
    }

    @Test
    fun `maps a failure while reading the transitions`() {
        assertEquals(4, exitCodeOf(FakeTransitioner(transitionsFailure = JiraNetworkError())))
    }

    @Test
    fun `maps a failure while moving the task`() {
        val transitioner = FakeTransitioner(transitionFailure = JiraAuthenticationError())

        val failure = AdvanceTaskUseCase(transitioner).execute(command()) as AdvanceTaskResult.Failure

        assertEquals(3, failure.exitCode)
        assertTrue(transitioner.executed.isEmpty())
    }

    private fun exitCodeOf(transitioner: FakeTransitioner): Int =
        (AdvanceTaskUseCase(transitioner).execute(command()) as AdvanceTaskResult.Failure).exitCode

    private companion object {
        val TASK_ISSUE = JiraIssue(
            key = "S15P11A108-257",
            issueTypeId = "10002",
            issueTypeName = "작업",
            statusId = "21",
            statusName = "진행 중",
            summary = "[AUTH-001 | FE] 이메일 회원가입 화면 구현",
        )
        val TO_DONE = JiraTransition("5", "31", "완료")
    }
}
