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
            collapsed = emptySet(),
        )

        assertEquals(
            listOf(SprintTaskGroup.TO_DO, SprintTaskGroup.IN_PROGRESS, SprintTaskGroup.DONE),
            groupsOf(rows),
        )
        assertEquals(listOf("A-1", "A-2", "A-3"), keysOf(rows))
    }

    @Test
    fun `folds away the finished work but keeps its header`() {
        // 스프린트가 갈수록 길어지는 그룹이고, 후속 커밋을 붙일 때만 필요하다.
        val rows = SprintTaskList.rowsOf(
            listOf(task("A-1", JiraStatusCategory.TO_DO), task("A-3", JiraStatusCategory.DONE)),
        )

        assertEquals(listOf(SprintTaskGroup.TO_DO, SprintTaskGroup.DONE), groupsOf(rows))
        assertEquals("접힌 그룹의 작업은 줄이 없다", listOf("A-1"), keysOf(rows))
    }

    @Test
    fun `says how many are hidden behind a folded header`() {
        val rows = SprintTaskList.rowsOf(
            listOf(task("A-3", JiraStatusCategory.DONE), task("A-4", JiraStatusCategory.DONE)),
        )

        val header = rows.filterIsInstance<SprintTaskRow.Header>().single()
        assertTrue(header.isCollapsed)
        assertEquals(2, header.taskCount)
        assertEquals("▸ 완료 (2)", header.title())
    }

    @Test
    fun `marks an open header so it can be told apart`() {
        val rows = SprintTaskList.rowsOf(listOf(task("A-1", JiraStatusCategory.TO_DO)))

        val header = rows.filterIsInstance<SprintTaskRow.Header>().single()
        assertFalse(header.isCollapsed)
        assertEquals("▾ 해야 할 일 (1)", header.title())
    }

    @Test
    fun `toggling a header folds it and toggling again opens it`() {
        val open = SprintTaskList.toggled(SprintTaskList.DEFAULT_COLLAPSED, SprintTaskGroup.DONE)
        val folded = SprintTaskList.toggled(open, SprintTaskGroup.DONE)

        assertTrue(SprintTaskGroup.DONE !in open)
        assertTrue(SprintTaskGroup.DONE in folded)
    }

    @Test
    fun `folds only the group that was asked for`() {
        val collapsed = SprintTaskList.toggled(emptySet(), SprintTaskGroup.TO_DO)

        assertEquals(setOf(SprintTaskGroup.TO_DO), collapsed)
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
