package io.github.gudwns1812.jiratask.ui.task

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.gudwns1812.jiratask.domain.jira.EmptySprintReason
import io.github.gudwns1812.jiratask.domain.jira.JiraStatusCategory
import io.github.gudwns1812.jiratask.domain.jira.SprintTask

/** 목록에서 고르는 화면. 목록이 없어도 일할 수 있어야 한다는 것이 핵심이다. */
class StartTaskDialogTest : BasePlatformTestCase() {

    private fun task(key: String, category: JiraStatusCategory = JiraStatusCategory.TO_DO, summary: String = "제목") =
        SprintTask(key, summary, "상태", category, inOpenSprint = true)

    private fun dialog(current: String? = null) = StartTaskDialog(project, current).also { disposeOnTearDown(it.disposable) }

    fun `test says it is loading before the list arrives`() {
        val dialog = dialog()

        assertEquals(StartTaskDialog.LOADING, dialog.statusText())
        assertFalse("아직 고를 것도 칠 것도 없다", dialog.isOKActionEnabled)
    }

    fun `test lists the tasks under their group headers`() {
        val dialog = dialog()

        dialog.show(listOf(task("S15P11A108-257"), task("S15P11A108-258", JiraStatusCategory.DONE)))

        val rows = (0 until dialog.taskList.model.size).map { dialog.taskList.model.getElementAt(it) }
        assertEquals(4, rows.size)
        assertTrue(rows[0] is SprintTaskRow.Header)
        assertTrue(rows[2] is SprintTaskRow.Header)
    }

    fun `test tells the user why the list is empty, not just that it is`() {
        // 스프린트 미시작과 배정 없음은 다음에 할 일이 전혀 다르다.
        val dialog = dialog()

        dialog.show(emptyList(), EmptySprintReason.NO_OPEN_SPRINT)

        assertEquals(EmptySprintReason.NO_OPEN_SPRINT.message, dialog.statusText())
    }

    fun `test falls back to the plain notice when the reason is unknown`() {
        val dialog = dialog()

        dialog.show(emptyList())

        assertEquals(EmptySprintReason.UNKNOWN.message, dialog.statusText())
    }

    fun `test still takes a typed number when the list could not be read`() {
        // Jira가 죽었다고 커밋을 못 하면 안 된다.
        val dialog = dialog()

        dialog.showProblem("Jira 서버와 통신할 수 없습니다.")
        dialog.taskNumberField.text = "257"

        assertEquals("Jira 서버와 통신할 수 없습니다.", dialog.statusText())
        assertTrue(dialog.isOKActionEnabled)
        assertEquals("257", dialog.choice().taskNumber)
        assertNull("무엇을 골랐는지는 모른다", dialog.choice().task)
    }

    fun `test fills the number from the task the user picked`() {
        val dialog = dialog()
        dialog.show(listOf(task("S15P11A108-257")))

        dialog.taskList.selectedIndex = 1

        assertEquals("257", dialog.taskNumberField.text)
        assertEquals("S15P11A108-257", dialog.choice().task?.key)
        assertTrue(dialog.choice().movesToInProgress())
    }

    fun `test warns instead of moving a finished task back`() {
        val dialog = dialog()
        dialog.show(listOf(task("S15P11A108-9", JiraStatusCategory.DONE)))

        dialog.taskList.selectedIndex = 1

        assertEquals(SprintTaskList.DONE_NOTICE, dialog.choice().notice())
        assertFalse(dialog.choice().movesToInProgress())
    }

    fun `test selecting a header picks no task`() {
        val dialog = dialog()
        dialog.show(listOf(task("S15P11A108-257")))

        dialog.taskList.selectedIndex = 0

        assertNull(dialog.choice().task)
    }

    fun `test rejects a number that is not a positive integer`() {
        val dialog = dialog()

        dialog.taskNumberField.text = "abc"
        assertFalse(dialog.isOKActionEnabled)
        assertNotNull(dialog.doValidate())

        dialog.taskNumberField.text = "0"
        assertFalse(dialog.isOKActionEnabled)

        dialog.taskNumberField.text = "257"
        assertTrue(dialog.isOKActionEnabled)
    }

    fun `test preselects the task that is already current`() {
        val dialog = dialog(current = "258")

        dialog.show(listOf(task("S15P11A108-257"), task("S15P11A108-258")))

        assertEquals("S15P11A108-258", dialog.choice().task?.key)
    }

    fun `test recognises a task after the number was typed by hand`() {
        val dialog = dialog()
        dialog.show(listOf(task("S15P11A108-9", JiraStatusCategory.DONE)))

        dialog.taskNumberField.text = "9"

        assertEquals("고른 것이 아니어도 아는 작업이면 알아본다", "S15P11A108-9", dialog.choice().task?.key)
        assertFalse(dialog.choice().movesToInProgress())
    }

    fun `test a reloaded list replaces the previous one`() {
        val dialog = dialog()
        dialog.show(listOf(task("S15P11A108-1"), task("S15P11A108-2")))

        dialog.show(listOf(task("S15P11A108-3")))

        assertEquals(2, dialog.taskList.model.size)
    }

    fun `test a failure after a list clears the rows`() {
        val dialog = dialog()
        dialog.show(listOf(task("S15P11A108-1")))

        dialog.showProblem("권한이 없습니다.")

        assertEquals(0, dialog.taskList.model.size)
        assertEquals("권한이 없습니다.", dialog.statusText())
    }
}
