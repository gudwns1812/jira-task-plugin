package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.jira.JiraAuthenticationError
import io.github.gudwns1812.jiratask.domain.jira.JiraNetworkError
import io.github.gudwns1812.jiratask.domain.jira.JiraRequestError
import io.github.gudwns1812.jiratask.domain.jira.TaskIssueTransitioner

data class AdvanceTaskCommand(
    val taskKey: String,
    val taskIssueTypeId: String,
    val targetStatusId: String,
    val targetStatusName: String,
)

sealed class AdvanceTaskResult {
    data class Success(val taskKey: String, val statusName: String) : AdvanceTaskResult()
    /** 이미 목표 상태다. 같은 작업에 커밋을 여러 번 하는 경우가 흔하므로 실패로 보지 않는다. */
    data class AlreadyThere(val taskKey: String, val statusName: String) : AdvanceTaskResult()
    data class Failure(val exitCode: Int, val message: String) : AdvanceTaskResult()
}

/**
 * 작업 이슈를 목표 상태로 옮긴다. 작업을 시작할 때는 진행 중으로, 커밋한 뒤에는 완료로 부른다.
 *
 * 목표 상태는 ID로 먼저 찾고, 없으면 이름으로 찾는다. Jira에서 상태는 프로젝트 전체가 공유하지만
 * 이슈 유형마다 워크플로가 달라 스토리에서 쓰던 상태 ID가 작업 워크플로에는 없을 수 있다.
 *
 * 이 유스케이스를 부르는 시점에는 git 작업(작업 번호 저장, 커밋)이 이미 끝나 있다.
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

        if (issue.statusId == command.targetStatusId || issue.statusName.equals(command.targetStatusName, true)) {
            return AdvanceTaskResult.AlreadyThere(command.taskKey, issue.statusName)
        }

        val transitions = try {
            transitioner.listTransitions(command.taskKey)
        } catch (error: Exception) {
            return toJiraFailure(error)
        }

        val transition = transitions.firstOrNull { it.toStatusId == command.targetStatusId }
            ?: transitions.firstOrNull { it.toStatusName.equals(command.targetStatusName, true) }
            ?: return AdvanceTaskResult.Failure(
                1,
                "'${command.targetStatusName}' 상태로 전환할 수 있는 Jira 워크플로를 찾지 못했습니다. (현재 상태: ${issue.statusName})",
            )

        return try {
            transitioner.transition(command.taskKey, transition.id)
            AdvanceTaskResult.Success(command.taskKey, command.targetStatusName)
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
