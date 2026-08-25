package io.github.gudwns1812.jiratask.ui.commit

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.gudwns1812.jiratask.application.CredentialStore
import io.github.gudwns1812.jiratask.infrastructure.JiraTaskConfigStorage
import io.github.gudwns1812.jiratask.infrastructure.PolicyConfigRepository
import io.github.gudwns1812.jiratask.ui.JiraTaskSettings
import io.github.gudwns1812.jiratask.ui.task.CurrentTaskState

class JiraTaskCommitDialogTest : BasePlatformTestCase() {

    private object NoCredentials : CredentialStore {
        override fun save(baseUrl: String, email: String, apiToken: String) = Unit
        override fun get(baseUrl: String, email: String): String? = null
    }

    override fun tearDown() {
        try {
            CurrentTaskState.getInstance(project).apply(null)
        } finally {
            super.tearDown()
        }
    }

    private fun settingsWithProjectKey(projectKey: String?): JiraTaskSettings {
        val repository = PolicyConfigRepository(JiraTaskConfigStorage())
        if (projectKey != null) {
            repository.setProjectKey(projectKey)
        }
        return JiraTaskSettings(repository, NoCredentials) { null }
    }

    fun `test builds the issue key from the saved task number and project key`() {
        CurrentTaskState.getInstance(project).apply("257")
        val factory = JiraTaskCheckinHandlerFactory(settingsWithProjectKey("S15P11A108"))

        assertEquals("S15P11A108-257", factory.issueKeyFor(project))
    }

    fun `test has no issue key without a saved task number`() {
        val factory = JiraTaskCheckinHandlerFactory(settingsWithProjectKey("S15P11A108"))

        assertNull(factory.issueKeyFor(project))
    }

    fun `test has no issue key without a configured project key`() {
        CurrentTaskState.getInstance(project).apply("257")
        val factory = JiraTaskCheckinHandlerFactory(settingsWithProjectKey(null))

        assertNull(factory.issueKeyFor(project))
    }

    fun `test commit dialog requires a message`() {
        val dialog = CommitMessageDialog(project, "S15P11A108-257")
        try {
            assertFalse(dialog.isOKActionEnabled)
            assertEquals("커밋 메시지를 입력하세요.", dialog.doValidate()!!.message)

            dialog.messageArea.text = "이메일 회원가입 폼 구현"

            assertTrue(dialog.isOKActionEnabled)
            assertEquals("이메일 회원가입 폼 구현", dialog.message())
            assertNull(dialog.doValidate())
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    fun `test commit dialog rejects a blank message`() {
        val dialog = CommitMessageDialog(project, null)
        try {
            dialog.messageArea.text = "   \n  "

            assertFalse(dialog.isOKActionEnabled)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }
}
