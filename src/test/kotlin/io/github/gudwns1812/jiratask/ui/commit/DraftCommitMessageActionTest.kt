package io.github.gudwns1812.jiratask.ui.commit

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.gudwns1812.jiratask.application.CommitDraftResult
import io.github.gudwns1812.jiratask.application.DraftCommitMessageUseCase
import io.github.gudwns1812.jiratask.domain.commit.CommitType
import io.github.gudwns1812.jiratask.domain.commit.WrittenCommitMessage
import java.io.File

/**
 * IDE 커밋 창의 메시지 칸을 채우는 액션. 창을 띄우지 않고 [CommitMessageTarget]으로 확인한다 —
 * 확인하려는 것은 Swing이 아니라 "무엇을 넣고 무엇을 남기는가"다.
 */
class DraftCommitMessageActionTest : BasePlatformTestCase() {

    private class FakeField(override var currentMessage: String?) : CommitMessageTarget {
        override fun setMessage(text: String) {
            currentMessage = text
        }
    }

    private val announced = mutableListOf<String>()

    private fun action(result: () -> CommitDraftResult, hints: MutableList<String?> = mutableListOf()) =
        DraftCommitMessageAction(
            draft = { _, _, hint ->
                hints += hint
                result()
            },
            background = { _, work -> work() },
            onEdt = { _, work -> work() },
            announce = { _, message -> announced += message },
        )

    private fun drafted(summary: String, type: CommitType = CommitType.FEAT) =
        CommitDraftResult.Drafted(WrittenCommitMessage(type, summary))

    private fun directory() = File(project.basePath ?: ".")

    fun `test fills the commit message field with the draft`() {
        val field = FakeField("")

        action({ drafted("회원가입 폼 구현") }).draftInto(project, directory(), field)

        assertEquals("feat: 회원가입 폼 구현", field.currentMessage)
    }

    fun `test keeps the issue key that was already in the field`() {
        // 키를 붙이는 것은 도구의 일이다(RULE.md 5.2). 초안 한 번에 사라지면 커밋 가드가 뒤늦게 경고한다.
        val field = FakeField("[S15P11A108-257] 쓰다 만 메시지")

        action({ drafted("회원가입 폼 구현") }).draftInto(project, directory(), field)

        assertEquals("[S15P11A108-257] feat: 회원가입 폼 구현", field.currentMessage)
    }

    fun `test replaces an issue key the draft made up with the real one`() {
        val field = FakeField("[S15P11A108-257] ")

        action({ drafted("회원가입 폼 구현", CommitType.REFACTOR) }).draftInto(project, directory(), field)

        assertEquals("[S15P11A108-257] refactor: 회원가입 폼 구현", field.currentMessage)
    }

    fun `test sends what the user already typed as a hint`() {
        val hints = mutableListOf<String?>()
        val field = FakeField("[S15P11A108-257] 폼 검증")

        action({ drafted("회원가입 폼 구현") }, hints).draftInto(project, directory(), field)

        assertEquals(listOf("[S15P11A108-257] 폼 검증"), hints)
    }

    fun `test leaves the message alone and says why when the draft is refused`() {
        val field = FakeField("[S15P11A108-257] 쓰던 메시지")

        action({ CommitDraftResult.Refused(DraftCommitMessageUseCase.DISABLED) })
            .draftInto(project, directory(), field)

        assertEquals("[S15P11A108-257] 쓰던 메시지", field.currentMessage)
        assertEquals(listOf(DraftCommitMessageUseCase.DISABLED), announced)
    }

    fun `test survives a draft that throws`() {
        val field = FakeField("")

        action({ throw RuntimeException("프로세스가 죽었다") }).draftInto(project, directory(), field)

        assertEquals("", field.currentMessage)
        assertEquals(listOf(DraftCommitMessageUseCase.FAILED), announced)
    }

    fun `test shows up only where there is a commit message field`() {
        val action = action({ drafted("무엇이든") })

        val withField = TestActionEvent.createTestEvent(action, contextWithMessageField())
        action.update(withField)
        assertTrue(withField.presentation.isEnabledAndVisible)

        val withoutField = TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project))
        action.update(withoutField)
        assertFalse("메시지 칸이 없으면 할 일이 없다", withoutField.presentation.isEnabledAndVisible)

        val withoutProject = TestActionEvent.createTestEvent(action, DataContext { null })
        action.update(withoutProject)
        assertFalse(withoutProject.presentation.isEnabledAndVisible)
    }

    private fun contextWithMessageField(): DataContext = SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, project)
        .add(VcsDataKeys.COMMIT_MESSAGE_CONTROL, CommitMessageI { })
        .build()

    override fun tearDown() {
        try {
            announced.clear()
        } finally {
            super.tearDown()
        }
    }
}
