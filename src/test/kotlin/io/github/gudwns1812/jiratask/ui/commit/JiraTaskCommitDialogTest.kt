package io.github.gudwns1812.jiratask.ui.commit

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.gudwns1812.jiratask.application.CommitDraftResult
import io.github.gudwns1812.jiratask.application.CredentialStore
import io.github.gudwns1812.jiratask.application.DraftCommitMessageUseCase
import io.github.gudwns1812.jiratask.domain.commit.CommitType
import io.github.gudwns1812.jiratask.domain.commit.WrittenCommitMessage
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

    /** 창을 띄우지 않고 버튼만 눌러 본다. 백그라운드와 EDT는 그 자리에서 돌린다. */
    private fun dialogWith(
        draft: (String?) -> CommitDraftResult,
        hints: MutableList<String?> = mutableListOf(),
    ) = CommitMessageDialog(
        project,
        "S15P11A108-257",
        draft = { hint ->
            hints += hint
            draft(hint)
        },
        background = { it() },
        onEdt = { it() },
    )

    fun `test the ai writes the commit message into the field`() {
        val dialog = dialogWith({ CommitDraftResult.Drafted(WrittenCommitMessage(CommitType.FEAT, "회원가입 폼 구현")) })
        try {
            dialog.requestDraft()

            assertEquals("접두사가 붙은 한 줄이 전부다", "feat: 회원가입 폼 구현", dialog.messageArea.text)
            assertEquals("", dialog.draftText())
            assertTrue("초안이 들어오면 커밋할 수 있어야 한다", dialog.isOKActionEnabled)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    fun `test what the user typed goes along as a hint`() {
        val hints = mutableListOf<String?>()
        val dialog = dialogWith({ CommitDraftResult.Drafted(WrittenCommitMessage(CommitType.FEAT, "회원가입 폼 구현")) }, hints)
        try {
            dialog.messageArea.text = "폼 검증"
            dialog.requestDraft()

            assertEquals(listOf("폼 검증"), hints)
            assertEquals("feat: 회원가입 폼 구현", dialog.messageArea.text)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    fun `test the dialog says why the ai refused and keeps what was typed`() {
        val dialog = dialogWith({ CommitDraftResult.Refused("Claude Code CLI를 찾을 수 없습니다.") })
        try {
            dialog.messageArea.text = "직접 쓴 메시지"
            dialog.requestDraft()

            assertEquals("Claude Code CLI를 찾을 수 없습니다.", dialog.draftText())
            assertEquals("실패한 초안이 쓰던 글을 지우면 안 된다", "직접 쓴 메시지", dialog.messageArea.text)
            assertTrue("초안이 실패해도 커밋은 막지 않는다", dialog.isOKActionEnabled)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    fun `test the dialog survives a draft that throws`() {
        val dialog = dialogWith({ throw RuntimeException("프로세스가 죽었다") })
        try {
            dialog.requestDraft()

            assertEquals(DraftCommitMessageUseCase.FAILED, dialog.draftText())
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    fun `test the ai button is there even when the ai is turned off`() {
        // 숨기면 기능이 있다는 것도, 켜러 가야 한다는 것도 알 길이 없다.
        val dialog = CommitMessageDialog(project, null, background = { it() }, onEdt = { it() })
        try {
            dialog.requestDraft()

            assertEquals(DraftCommitMessageUseCase.DISABLED, dialog.draftText())
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }
}
