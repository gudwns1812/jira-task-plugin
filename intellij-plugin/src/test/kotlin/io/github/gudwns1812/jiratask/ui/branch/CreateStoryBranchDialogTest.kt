package io.github.gudwns1812.jiratask.ui.branch

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CreateStoryBranchDialogTest : BasePlatformTestCase() {

    private fun withDialog(block: (CreateStoryBranchDialog) -> Unit) {
        val dialog = CreateStoryBranchDialog(project)
        try {
            block(dialog)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    fun `test keeps the ok action disabled until both inputs are valid`() {
        withDialog { dialog ->
            assertFalse(dialog.isOKActionEnabled)

            dialog.storyNumberField.text = "100"
            assertFalse("작업명이 없으면 아직 만들 수 없다", dialog.isOKActionEnabled)

            dialog.nameField.text = "signup"
            assertTrue(dialog.isOKActionEnabled)

            dialog.nameField.text = "Signup"
            assertFalse("대문자는 브랜치 이름으로 쓸 수 없다", dialog.isOKActionEnabled)
        }
    }

    fun `test explains the problem through the dialog validation`() {
        withDialog { dialog ->
            dialog.storyNumberField.text = "0"
            dialog.nameField.text = "signup"

            val info = dialog.doValidate()

            assertNotNull(info)
            assertTrue(info!!.message.contains("스토리 번호"))
        }
    }

    fun `test collects the typed values`() {
        withDialog { dialog ->
            dialog.storyNumberField.text = " 100 "
            dialog.nameField.text = " signup "

            assertEquals(BranchFormInput("100", "signup"), dialog.values().trimmed())
        }
    }

    fun `test is enabled only for a project with a base path`() {
        val action = CreateStoryBranchAction()

        val withProject = TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project))
        action.update(withProject)
        assertNotNull("테스트 픽스처 프로젝트에는 basePath가 있다", project.basePath)
        assertTrue(withProject.presentation.isEnabled)

        val withoutProject = TestActionEvent.createTestEvent(action, DataContext { null })
        action.update(withoutProject)
        assertFalse("프로젝트가 없으면 브랜치를 만들 수 없다", withoutProject.presentation.isEnabled)
    }
}
