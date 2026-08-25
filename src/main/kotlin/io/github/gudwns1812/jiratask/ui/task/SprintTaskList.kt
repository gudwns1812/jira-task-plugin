package io.github.gudwns1812.jiratask.ui.task

import io.github.gudwns1812.jiratask.domain.jira.JiraStatusCategory
import io.github.gudwns1812.jiratask.domain.jira.SprintTask

/**
 * 목록에서 작업이 놓이는 자리. 상태 이름이 아니라 카테고리로 묶는다(RULE.md 6.1).
 *
 * `해야 할 일`이 맨 위인 것은 이 화면을 여는 이유의 대부분이 새 작업을 시작하는 것이기 때문이다.
 * 작업은 하나를 끝내고 다음으로 넘어가므로(RULE.md 5.1) `진행 중`은 보통 한 건이고,
 * 다음 날 이어서 할 때만 필요하다.
 */
enum class SprintTaskGroup(val title: String) {
    TO_DO("해야 할 일"),
    IN_PROGRESS("진행 중"),
    DONE("완료"),
    ;

    companion object {
        fun of(task: SprintTask): SprintTaskGroup = when (task.statusCategory) {
            JiraStatusCategory.IN_PROGRESS -> IN_PROGRESS
            JiraStatusCategory.DONE -> DONE
            // 모르는 상태는 끝난 것으로 보지 않는다. 고를 수 있는 자리에 둔다.
            JiraStatusCategory.TO_DO, JiraStatusCategory.UNKNOWN -> TO_DO
        }
    }
}

/** 목록의 한 줄. 그룹 머리글이거나 작업이다. */
sealed interface SprintTaskRow {
    data class Header(val group: SprintTaskGroup) : SprintTaskRow
    data class Task(val task: SprintTask, val group: SprintTaskGroup) : SprintTaskRow
}

object SprintTaskList {

    /** 그룹 순서대로 늘어놓는다. 빈 그룹은 머리글도 만들지 않는다. */
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

    /** 검색어에 걸리는 작업만 남긴다. 키와 제목을 대소문자 없이 본다. */
    fun matching(tasks: List<SprintTask>, text: String): List<SprintTask> {
        val needle = text.trim()
        if (needle.isEmpty()) {
            return tasks
        }
        return tasks.filter { it.key.contains(needle, ignoreCase = true) || it.summary.contains(needle, ignoreCase = true) }
    }

    /**
     * 이 작업을 고르면 `진행 중`으로 옮길 것인가.
     *
     * 끝난 작업은 옮기지 않는다. 후속 커밋에 키만 붙이고 보드는 그대로 둔다 —
     * automation도 되돌리지 않는 규칙이다(RULE.md 4.3).
     */
    fun movesToInProgress(task: SprintTask): Boolean = !task.statusCategory.isDone

    /** 고른 작업이 끝난 것일 때 화면에 붙이는 안내. */
    const val DONE_NOTICE: String = "이미 완료된 작업입니다. 커밋에만 사용하고 상태는 그대로 둡니다."
}
