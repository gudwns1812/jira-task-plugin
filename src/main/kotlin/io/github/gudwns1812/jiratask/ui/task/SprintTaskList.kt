package io.github.gudwns1812.jiratask.ui.task

import io.github.gudwns1812.jiratask.domain.jira.JiraStatusCategory
import io.github.gudwns1812.jiratask.domain.jira.SprintTask

enum class SprintTaskGroup(val title: String) {
    TO_DO("해야 할 일"),
    IN_PROGRESS("진행 중"),
    DONE("완료"),
    ;

    companion object {
        fun of(task: SprintTask): SprintTaskGroup = when (task.statusCategory) {
            JiraStatusCategory.IN_PROGRESS -> IN_PROGRESS
            JiraStatusCategory.DONE -> DONE
            JiraStatusCategory.TO_DO, JiraStatusCategory.UNKNOWN -> TO_DO
        }
    }
}

sealed interface SprintTaskRow {
    data class Header(val group: SprintTaskGroup) : SprintTaskRow
    data class Task(val task: SprintTask, val group: SprintTaskGroup) : SprintTaskRow
}

object SprintTaskList {
    fun rowsOf(tasks: List<SprintTask>): List<SprintTaskRow> {
        val grouped = tasks.groupBy(SprintTaskGroup::of)

        return SprintTaskGroup.entries.flatMap { group ->
            val members = grouped[group].orEmpty()
            if (members.isEmpty()) {
                emptyList()
            } else {
                listOf(SprintTaskRow.Header(group)) + members.map { SprintTaskRow.Task(it, group) }
            }
        }
    }

    fun movesToInProgress(task: SprintTask): Boolean = !task.statusCategory.isDone

    const val DONE_NOTICE: String = "이미 완료된 작업입니다. 커밋에만 사용하고 상태는 그대로 둡니다."

    const val FILTER_NOTICE: String = "내 담당 · 열린 스프린트 · 작업(Task) 유형만 보입니다."

    fun excludedNotice(otherAssigneeCount: Int): String =
        if (otherAssigneeCount > 0) "$FILTER_NOTICE 다른 담당자 ${otherAssigneeCount}건은 빠졌습니다." else FILTER_NOTICE
}
