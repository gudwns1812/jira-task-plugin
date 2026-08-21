package io.github.gudwns1812.jiratask

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CreateJiraIssueActionTest : BasePlatformTestCase() {

    fun `test action performed shows placeholder message without throwing`() {
        val shownMessages = mutableListOf<String>()
        val previousDialog = TestDialogManager.setTestDialog(TestDialog { message ->
            shownMessages.add(message)
            0
        })
        try {
            val action = CreateJiraIssueAction()
            val event = TestActionEvent.createTestEvent(action)

            action.actionPerformed(event)

            assertSize(1, shownMessages)
            assertTrue(shownMessages.single().contains("뼈대"))
        } finally {
            TestDialogManager.setTestDialog(previousDialog)
        }
    }

    fun `test update marks action enabled and visible`() {
        val action = CreateJiraIssueAction()
        val event = TestActionEvent.createTestEvent(action)

        action.update(event)

        assertTrue(event.presentation.isEnabledAndVisible)
    }

    fun `test action is registered under expected id from plugin descriptor`() {
        val registered = ActionManager.getInstance().getAction("JiraTask.CreateIssue")

        assertNotNull("plugin.xml에 선언된 액션 ID로 등록되어 있어야 한다", registered)
        assertTrue(registered is CreateJiraIssueAction)
    }
}
