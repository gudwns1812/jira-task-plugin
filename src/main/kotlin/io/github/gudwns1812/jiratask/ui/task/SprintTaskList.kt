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

    /** 그룹 머리글. 눌러서 접고 편다. [taskCount]는 접혀 있어도 몇 건인지 알려 준다. */
    data class Header(
        val group: SprintTaskGroup,
        val taskCount: Int = 0,
        val isCollapsed: Boolean = false,
    ) : SprintTaskRow {
        /** `▾ 해야 할 일 (3)` */
        fun title(): String = "${if (isCollapsed) "▸" else "▾"} ${group.title} ($taskCount)"
    }

    data class Task(val task: SprintTask, val group: SprintTaskGroup) : SprintTaskRow
}

object SprintTaskList {

    /**
     * 그룹 순서대로 늘어놓는다. 빈 그룹은 머리글도 만들지 않는다.
     *
     * [collapsed]에 든 그룹은 머리글만 남기고 작업을 감춘다. 머리글은 접혀 있어도 건수를 보여 주므로
     * 접었다고 무엇이 사라졌는지 모르게 되지는 않는다.
     */
    @JvmOverloads
    fun rowsOf(
        tasks: List<SprintTask>,
        collapsed: Set<SprintTaskGroup> = DEFAULT_COLLAPSED,
    ): List<SprintTaskRow> {
        val grouped = tasks.groupBy(SprintTaskGroup::of)

        return SprintTaskGroup.entries.flatMap { group ->
            val members = grouped[group].orEmpty()
            if (members.isEmpty()) {
                return@flatMap emptyList()
            }

            val isCollapsed = group in collapsed
            val header = SprintTaskRow.Header(group, members.size, isCollapsed)
            if (isCollapsed) {
                listOf(header)
            } else {
                listOf(header) + members.map { SprintTaskRow.Task(it, group) }
            }
        }
    }

    /** 머리글을 눌렀을 때의 다음 상태. */
    fun toggled(collapsed: Set<SprintTaskGroup>, group: SprintTaskGroup): Set<SprintTaskGroup> =
        if (group in collapsed) collapsed - group else collapsed + group

    /**
     * 처음에 접어 두는 그룹.
     *
     * `완료`만 접는다 — 스프린트가 진행될수록 가장 길어지지만 후속 커밋을 붙일 때만 필요하다.
     * 지금 할 일과 하던 일은 이 화면을 여는 이유 자체이므로 접지 않는다.
     */
    val DEFAULT_COLLAPSED: Set<SprintTaskGroup> = setOf(SprintTaskGroup.DONE)

    /**
     * 이 작업을 고르면 `진행 중`으로 옮길 것인가.
     *
     * 끝난 작업은 옮기지 않는다. 후속 커밋에 키만 붙이고 보드는 그대로 둔다 —
     * automation도 되돌리지 않는 규칙이다(RULE.md 4.3).
     */
    fun movesToInProgress(task: SprintTask): Boolean = !task.statusCategory.isDone

    /** 고른 작업이 끝난 것일 때 화면에 붙이는 안내. */
    const val DONE_NOTICE: String = "이미 완료된 작업입니다. 커밋에만 사용하고 상태는 그대로 둡니다."

    /**
     * 이 목록이 무엇만 보여 주는지. **늘 보이게 둔다.**
     *
     * 방금 만든 이슈가 목록에 없을 때, 조건이 보이지 않으면 도구가 고장 난 것처럼 보인다.
     * 셋 중 어느 것이 어긋났는지는 사용자가 Jira를 보면 바로 안다 — 조건을 알기만 하면.
     */
    const val FILTER_NOTICE: String = "내 담당 · 열린 스프린트 · 작업(Task) 유형만 보입니다."

    /** 내 담당이 아니라 빠진 작업이 있으면 덧붙이는 말. */
    fun excludedNotice(otherAssigneeCount: Int): String =
        if (otherAssigneeCount > 0) "$FILTER_NOTICE 다른 담당자 ${otherAssigneeCount}건은 빠졌습니다." else FILTER_NOTICE
}
