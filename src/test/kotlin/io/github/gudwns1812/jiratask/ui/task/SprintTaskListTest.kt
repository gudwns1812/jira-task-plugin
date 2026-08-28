package io.github.gudwns1812.jiratask.ui.task

import io.github.gudwns1812.jiratask.domain.jira.JiraStatusCategory
import io.github.gudwns1812.jiratask.domain.jira.SprintTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 목록의 순서와 "고르면 옮길 것인가" 규칙. */
class SprintTaskListTest {

    private fun task(key: String, category: JiraStatusCategory, summary: String = "제목") =
        SprintTask(key, summary, "상태", category, inOpenSprint = true)

    private fun groupsOf(rows: List<SprintTaskRow>) =
        rows.filterIsInstance<SprintTaskRow.Header>().map { it.group }

    private fun keysOf(rows: List<SprintTaskRow>) =
        rows.filterIsInstance<SprintTaskRow.Task>().map { it.task.key }

    @Test
    fun `puts the work to start first and the finished work last`() {
        val rows = SprintTaskList.rowsOf(
            listOf(
                task("A-3", JiraStatusCategory.DONE),
                task("A-2", JiraStatusCategory.IN_PROGRESS),
                task("A-1", JiraStatusCategory.TO_DO),
            ),
        )

        assertEquals(
            listOf(SprintTaskGroup.TO_DO, SprintTaskGroup.IN_PROGRESS, SprintTaskGroup.DONE),
            groupsOf(rows),
        )
        assertEquals(listOf("A-1", "A-2", "A-3"), keysOf(rows))
    }

    @Test
    fun `keeps the order jira gave inside one group`() {
        val rows = SprintTaskList.rowsOf(
            listOf(task("A-9", JiraStatusCategory.TO_DO), task("A-2", JiraStatusCategory.TO_DO)),
        )

        assertEquals(listOf("A-9", "A-2"), keysOf(rows))
    }

    @Test
    fun `writes no header for a group with nothing in it`() {
        val rows = SprintTaskList.rowsOf(listOf(task("A-1", JiraStatusCategory.TO_DO)))

        assertEquals(listOf(SprintTaskGroup.TO_DO), groupsOf(rows))
        assertEquals(2, rows.size)
    }

    @Test
    fun `has nothing to show for an empty sprint`() {
        assertTrue(SprintTaskList.rowsOf(emptyList()).isEmpty())
    }

    @Test
    fun `treats an unknown status as work still to do`() {
        val rows = SprintTaskList.rowsOf(listOf(task("A-1", JiraStatusCategory.UNKNOWN)))

        assertEquals(listOf(SprintTaskGroup.TO_DO), groupsOf(rows))
    }

    @Test
    fun `moves an unfinished task to in progress but leaves a finished one alone`() {
        // 끝난 작업을 되돌리지 않는다(RULE.md 4.3).
        assertTrue(SprintTaskList.movesToInProgress(task("A-1", JiraStatusCategory.TO_DO)))
        assertTrue(SprintTaskList.movesToInProgress(task("A-2", JiraStatusCategory.IN_PROGRESS)))
        assertTrue(SprintTaskList.movesToInProgress(task("A-4", JiraStatusCategory.UNKNOWN)))
        assertFalse(SprintTaskList.movesToInProgress(task("A-3", JiraStatusCategory.DONE)))
    }

    @Test
    fun `explains itself when a finished task is chosen`() {
        val done = StartTaskChoice("3", task("A-3", JiraStatusCategory.DONE))

        assertEquals(SprintTaskList.DONE_NOTICE, done.notice())
        assertFalse(done.movesToInProgress())
    }

    @Test
    fun `says nothing extra for an ordinary choice`() {
        assertEquals(null, StartTaskChoice("1", task("A-1", JiraStatusCategory.TO_DO)).notice())
    }

    @Test
    fun `moves a hand typed number because nothing is known about it`() {
        // 목록을 못 불러온 경우다. 옮겨 보고 실패하면 경고만 한다.
        val typed = StartTaskChoice("257", null)

        assertTrue(typed.movesToInProgress())
        assertEquals(null, typed.notice())
    }
}
