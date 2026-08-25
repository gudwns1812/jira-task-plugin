package io.github.gudwns1812.jiratask.ui.branch

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.gudwns1812.jiratask.application.CredentialStore
import io.github.gudwns1812.jiratask.infrastructure.JiraTaskConfigStorage
import io.github.gudwns1812.jiratask.infrastructure.PolicyConfigRepository
import io.github.gudwns1812.jiratask.ui.JiraTaskAvailability
import io.github.gudwns1812.jiratask.ui.JiraTaskSettings

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

    fun `test the action needs both an open project and a finished setup`() {
        // 활성화 규칙 전체는 JiraTaskActionUpdateTest가 다룬다. 여기서는 이 액션이 그 규칙을 쓰는지만 본다.
        val repository = PolicyConfigRepository(JiraTaskConfigStorage()).apply {
            saveConfig(
                mapOf(
                    "projectKey" to "S15P11A108",
                    "jira" to mapOf(
                        "baseUrl" to "https://team.atlassian.net",
                        "issueTypes" to mapOf("story" to mapOf("id" to "10001", "name" to "스토리")),
                        "statuses" to mapOf("story" to mapOf("start" to mapOf("id" to "21", "name" to "진행 중"))),
                    ),
                ),
            )
        }
        val action = CreateStoryBranchAction(
            JiraTaskSettings(repository, NoCredentials) { null },
            JiraTaskAvailability(repository),
        )

        val withProject = TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project))
        action.update(withProject)
        assertTrue(withProject.presentation.isEnabled)

        val withoutProject = TestActionEvent.createTestEvent(action, DataContext { null })
        action.update(withoutProject)
        assertFalse("프로젝트가 없으면 브랜치를 만들 수 없다", withoutProject.presentation.isEnabled)
        assertEquals(JiraTaskAvailability.NO_PROJECT, withoutProject.presentation.description)
    }

    private object NoCredentials : CredentialStore {
        override fun save(baseUrl: String, email: String, apiToken: String) = Unit
        override fun get(baseUrl: String, email: String): String? = null
    }
}
