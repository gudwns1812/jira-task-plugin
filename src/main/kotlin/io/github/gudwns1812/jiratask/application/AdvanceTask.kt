package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.jira.JiraAuthenticationError
import io.github.gudwns1812.jiratask.domain.jira.JiraNetworkError
import io.github.gudwns1812.jiratask.domain.jira.JiraRequestError
import io.github.gudwns1812.jiratask.domain.jira.JiraStatusCategory
import io.github.gudwns1812.jiratask.domain.jira.TaskIssueTransitioner

data class AdvanceTaskCommand(val taskKey: String, val taskIssueTypeId: String)

sealed class AdvanceTaskResult {
    data class Success(val taskKey: String, val statusName: String) : AdvanceTaskResult()

    /** 이미 진행 중이다. 같은 작업을 두 번 고르거나 automation이 먼저 옮긴 경우가 흔하다. */
    data class AlreadyThere(val taskKey: String, val statusName: String) : AdvanceTaskResult()

    /** 이미 끝난 작업이라 그대로 뒀다. 후속 커밋에 키만 붙이고 보드는 건드리지 않는다. */
    data class LeftAlone(val taskKey: String, val statusName: String) : AdvanceTaskResult()
    data class Failure(val exitCode: Int, val message: String) : AdvanceTaskResult()
}

/**
 * 작업 이슈를 `진행 중`으로 옮긴다. 담당자가 작업을 고른 직후에 부른다.
 *
 * **이미 `완료`인 작업의 상태는 어떤 경우에도 바꾸지 않는다.**
 *
 * **어느 상태가 "진행 중"인지 묻지 않는다.** Jira가 상태마다 붙여 주는 카테고리
 * (`To Do` / `In Progress` / `Done`)가 이미 답이다. 상태 이름은 사이트 언어와 워크플로마다
 * 다르지만 카테고리는 API가 보장하므로, 그 이슈의 워크플로에서 카테고리가 `In Progress`인
 * 전환을 골라 쓴다. 설정에 상태 ID를 저장해 두면 이슈 유형마다 워크플로가 달라 어긋난다.
 *
 * 이 유스케이스를 부르는 시점에는 작업 번호 저장이 이미 끝나 있다.
 * 그래서 Jira 실패는 되돌리지 않고 그대로 알린다.
 */
class AdvanceTaskUseCase(private val transitioner: TaskIssueTransitioner) {

    fun execute(command: AdvanceTaskCommand): AdvanceTaskResult {
        val issue = try {
            transitioner.getIssue(command.taskKey)
        } catch (error: Exception) {
            return toJiraFailure(error)
        }

        if (issue.issueTypeId != command.taskIssueTypeId) {
            return AdvanceTaskResult.Failure(
                1,
                "${command.taskKey} 이슈는 작업 타입이 아닙니다. (실제 타입: ${issue.issueTypeName})",
            )
        }

        // 이미 진행 중이면 아무것도 하지 않는다. 플러그인과 automation이 같은 전환을 겹쳐
        // 실행하므로 멱등해야 한다(RULE.md 4.3).
        if (issue.statusCategory == JiraStatusCategory.IN_PROGRESS) {
            return AdvanceTaskResult.AlreadyThere(command.taskKey, issue.statusName)
        }

        // **끝난 작업은 절대 되돌리지 않는다**(RULE.md 4.3). 화면에서도 막고 있지만 그 판단은
        // 목록에 있는 작업에만 닿는다 — 번호를 직접 친 경우에는 여기가 유일한 방벽이다.
        // 이슈는 위에서 이미 읽었으므로 이 검사에 드는 호출은 없다.
        if (issue.statusCategory == JiraStatusCategory.DONE) {
            return AdvanceTaskResult.LeftAlone(command.taskKey, issue.statusName)
        }

        val transitions = try {
            transitioner.listTransitions(command.taskKey)
        } catch (error: Exception) {
            return toJiraFailure(error)
        }

        val transition = transitions.firstOrNull { it.toStatusCategory == JiraStatusCategory.IN_PROGRESS }
            ?: return AdvanceTaskResult.Failure(
                1,
                "이 작업의 Jira 워크플로에 '진행 중' 단계로 가는 전환이 없습니다. (현재 상태: ${issue.statusName})",
            )

        return try {
            transitioner.transition(command.taskKey, transition.id)
            AdvanceTaskResult.Success(command.taskKey, transition.toStatusName)
        } catch (error: Exception) {
            toJiraFailure(error)
        }
    }

    private fun toJiraFailure(error: Exception): AdvanceTaskResult.Failure = when (error) {
        is JiraAuthenticationError -> AdvanceTaskResult.Failure(3, messageOf(error))
        is JiraNetworkError -> AdvanceTaskResult.Failure(4, messageOf(error))
        is JiraRequestError -> AdvanceTaskResult.Failure(1, messageOf(error))
        else -> AdvanceTaskResult.Failure(4, "Jira 작업 이슈를 처리하지 못했습니다.")
    }

    private fun messageOf(error: Exception): String = error.message ?: "예상하지 못한 오류가 발생했습니다."
}
