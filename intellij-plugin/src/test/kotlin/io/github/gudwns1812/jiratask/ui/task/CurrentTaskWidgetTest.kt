package io.github.gudwns1812.jiratask.ui.task

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CurrentTaskWidgetTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            CurrentTaskState.getInstance(project).apply(null)
        } finally {
            super.tearDown()
        }
    }

    fun `test widget follows the cached task number`() {
        val widget = CurrentTaskWidget(project)
        val presentation = widget.getPresentation() as StatusBarWidget.TextPresentation

        assertEquals(CurrentTaskText.NONE, presentation.getText())

        CurrentTaskState.getInstance(project).apply("257")

        assertEquals("Jira: 257", presentation.getText())
    }

    fun `test widget factory is available for a project with a base path`() {
        val factory = CurrentTaskWidgetFactory()

        assertEquals("JiraTask.CurrentTask", factory.id)
        assertTrue(factory.isAvailable(project))
        val widget = factory.createWidget(project)
        try {
            assertEquals(CurrentTaskWidgetFactory.ID, widget.ID())
        } finally {
            Disposer.dispose(widget)
        }
    }

    fun `test reading the task number outside a git repository leaves it empty`() {
        val state = CurrentTaskState.getInstance(project)
        state.apply("257")

        state.refresh()

        assertNull("테스트 픽스처 프로젝트는 git 저장소가 아니다", state.taskNumber)
    }

    fun `test start task dialog prefills the current number and validates it`() {
        val dialog = StartTaskDialog(project, "257")
        try {
            assertEquals("257", dialog.taskNumberField.text)
            assertTrue(dialog.isOKActionEnabled)

            dialog.taskNumberField.text = "0"

            assertFalse(dialog.isOKActionEnabled)
            assertTrue(dialog.doValidate()!!.message.contains("작업 번호"))
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    fun `test start task dialog starts disabled when nothing is stored`() {
        val dialog = StartTaskDialog(project, null)
        try {
            assertEquals("", dialog.taskNumberField.text)
            assertFalse(dialog.isOKActionEnabled)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    fun `test start task action needs a project`() {
        val action = StartTaskAction()

        val withProject = TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project))
        action.update(withProject)
        assertTrue(withProject.presentation.isEnabled)

        val withoutProject = TestActionEvent.createTestEvent(action, DataContext { null })
        action.update(withoutProject)
        assertFalse(withoutProject.presentation.isEnabled)
    }
}
