package io.github.gudwns1812.jiratask.domain.jira

enum class JiraStatusCategory {
    TO_DO,
    IN_PROGRESS,
    DONE,
    UNKNOWN,
    ;

    val isDone: Boolean get() = this == DONE

    companion object {
        fun ofKey(key: String?): JiraStatusCategory = when (key?.trim()?.lowercase()) {
            "new", "undefined" -> TO_DO
            "indeterminate" -> IN_PROGRESS
            "done" -> DONE
            else -> UNKNOWN
        }
    }
}

data class SprintTask(
    val key: String,
    val summary: String,
    val statusName: String,
    val statusCategory: JiraStatusCategory,
    val inOpenSprint: Boolean,
) {
    fun label(): String = if (summary.isBlank()) key else "$key  $summary"
}

interface SprintTaskReader {
    fun currentSprintTasks(projectKey: String, taskIssueTypeId: String): List<SprintTask>

    fun task(issueKey: String): SprintTask?

    fun searchByText(projectKey: String, text: String, limit: Int): List<SprintTask>

    fun countTasks(projectKey: String, taskIssueTypeId: String, mineOnly: Boolean, openSprintOnly: Boolean): Int
}

enum class EmptySprintReason(val message: String) {
    NO_TASK_TYPE("이 프로젝트에 '작업' 유형 이슈가 없습니다. 설정 마법사에서 작업 유형을 다시 확인하세요."),
    NO_OPEN_SPRINT("열린 스프린트가 없습니다. Jira 보드에서 스프린트를 시작(Start Sprint)해야 합니다."),
    NONE_ASSIGNED_TO_ME("열린 스프린트에 작업이 있지만 나에게 배정된 것이 없습니다. Jira에서 담당자를 확인하세요."),
    NOT_IN_SPRINT("내 작업이 있지만 열린 스프린트에 담겨 있지 않습니다. 스프린트에 넣거나 시작하세요."),
    UNKNOWN("현재 스프린트에 나에게 배정된 작업이 없습니다."),
    ;

    companion object {
        fun of(anyTask: Int, mineAnywhere: Int, anyInSprint: Int): EmptySprintReason = when {
            anyTask == 0 -> NO_TASK_TYPE
            anyInSprint == 0 -> NO_OPEN_SPRINT
            mineAnywhere == 0 -> NONE_ASSIGNED_TO_ME
            else -> NOT_IN_SPRINT
        }
    }
}

data class JiraIssueDetail(
    val key: String,
    val summary: String,
    val description: String,
    val statusName: String,
    val assignee: String,
    val sprint: String,
)

interface JiraIssueEditor {
    fun detail(issueKey: String): JiraIssueDetail?

    fun updateSummaryAndDescription(issueKey: String, summary: String, description: String)
}
