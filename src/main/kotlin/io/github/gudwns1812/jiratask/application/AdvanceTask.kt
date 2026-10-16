package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.jira.JiraAuthenticationError
import io.github.gudwns1812.jiratask.domain.jira.JiraNetworkError
import io.github.gudwns1812.jiratask.domain.jira.JiraRequestError
import io.github.gudwns1812.jiratask.domain.jira.JiraStatusCategory
import io.github.gudwns1812.jiratask.domain.jira.TaskIssueTransitioner

data class AdvanceTaskCommand(val taskKey: String, val taskIssueTypeId: String)

sealed class AdvanceTaskResult {
    data class Success(val taskKey: String, val statusName: String) : AdvanceTaskResult()

    data class AlreadyThere(val taskKey: String, val statusName: String) : AdvanceTaskResult()

    data class LeftAlone(val taskKey: String, val statusName: String) : AdvanceTaskResult()
    data class Failure(val exitCode: Int, val message: String) : AdvanceTaskResult()
}

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

        if (issue.statusCategory == JiraStatusCategory.IN_PROGRESS) {
            return AdvanceTaskResult.AlreadyThere(command.taskKey, issue.statusName)
        }

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
