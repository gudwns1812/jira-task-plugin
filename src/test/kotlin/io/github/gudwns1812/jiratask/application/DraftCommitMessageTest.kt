package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.commit.CommitDraftOutcome
import io.github.gudwns1812.jiratask.domain.commit.CommitDraftRequest
import io.github.gudwns1812.jiratask.domain.commit.CommitMessageWriter
import io.github.gudwns1812.jiratask.domain.commit.CommitType
import io.github.gudwns1812.jiratask.domain.commit.PendingChanges
import io.github.gudwns1812.jiratask.domain.commit.WrittenCommitMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftCommitMessageTest {

    /** 무엇을 받았는지 기억하는 가짜 작성자. 보낸 것을 확인하는 데 쓴다. */
    private class Recording(
        private val outcome: CommitDraftOutcome = CommitDraftOutcome.Written(WrittenCommitMessage(CommitType.FEAT, "회원가입 폼 구현")),
    ) : CommitMessageWriter {
        var request: CommitDraftRequest? = null

        override fun write(request: CommitDraftRequest): CommitDraftOutcome {
            this.request = request
            return outcome
        }
    }

    private fun state(
        issueKey: String? = "S15P11A108-257",
        branch: String? = "feature/signup",
        hint: String? = null,
        changes: (Boolean) -> PendingChanges = { includeDiff ->
            PendingChanges(listOf("src/Signup.kt"), if (includeDiff) "+val x = 1" else "")
        },
    ) = CommitDraftState(issueKey = issueKey, branch = branch, hint = hint, changes = changes)

    @Test
    fun `writes a commit message from what is about to be committed`() {
        val writer = Recording(CommitDraftOutcome.Written(WrittenCommitMessage(CommitType.FEAT, "회원가입 폼 구현")))

        val result = DraftCommitMessageUseCase(writer).draft(state())

        val drafted = result as CommitDraftResult.Drafted
        assertEquals("feat: 회원가입 폼 구현", drafted.message.subject)
        assertEquals("S15P11A108-257", writer.request?.issueKey)
        assertEquals(listOf("src/Signup.kt"), writer.request?.changedFiles)
    }

    @Test
    fun `sends the diff only when the setting says so`() {
        val on = Recording()
        DraftCommitMessageUseCase(on, sendDiff = true).draft(state())
        assertEquals("+val x = 1", on.request?.diff)

        val off = Recording()
        DraftCommitMessageUseCase(off, sendDiff = false).draft(state())
        assertNull("꺼져 있으면 변경 내용은 담기지 않는다", off.request?.diff)
    }

    @Test
    fun `does not even read the diff when the setting is off`() {
        // 보내지 않을 것을 읽지도 않는다. 읽어 두면 언젠가 실수로 실려 나간다.
        var askedFor: Boolean? = null
        val writer = Recording()

        DraftCommitMessageUseCase(writer, sendDiff = false).draft(
            state(changes = { includeDiff ->
                askedFor = includeDiff
                PendingChanges(listOf("src/Signup.kt"), "+val x = 1")
            }),
        )

        assertFalse(askedFor!!)
        assertNull(writer.request?.diff)
    }

    @Test
    fun `carries the words the user already typed`() {
        val writer = Recording()

        DraftCommitMessageUseCase(writer).draft(state(hint = "폼 검증"))

        assertEquals("폼 검증", writer.request?.hint)
    }

    @Test
    fun `refuses when the ai is turned off`() {
        val result = DraftCommitMessageUseCase(writer = null).draft(state())

        assertEquals(DraftCommitMessageUseCase.DISABLED, (result as CommitDraftResult.Refused).message)
    }

    @Test
    fun `refuses when there is nothing to commit`() {
        val writer = Recording()

        val result = DraftCommitMessageUseCase(writer)
            .draft(state(changes = { PendingChanges(emptyList()) }))

        assertEquals(DraftCommitMessageUseCase.NO_CHANGES, (result as CommitDraftResult.Refused).message)
        assertNull("재료가 없으면 부르지도 않는다", writer.request)
    }

    @Test
    fun `refuses when git cannot be read`() {
        val result = DraftCommitMessageUseCase(Recording())
            .draft(state(changes = { throw IllegalStateException("git 없음") }))

        assertEquals(DraftCommitMessageUseCase.NO_GIT, (result as CommitDraftResult.Refused).message)
    }

    @Test
    fun `passes the writer's own reason up`() {
        val unavailable = DraftCommitMessageUseCase(
            CommitMessageWriter { CommitDraftOutcome.Unavailable("Claude Code CLI를 찾을 수 없습니다.") },
        ).draft(state())
        assertEquals("Claude Code CLI를 찾을 수 없습니다.", (unavailable as CommitDraftResult.Refused).message)

        val failed = DraftCommitMessageUseCase(
            CommitMessageWriter { CommitDraftOutcome.Failed("사용량 한도에 걸렸습니다.") },
        ).draft(state())
        assertEquals("사용량 한도에 걸렸습니다.", (failed as CommitDraftResult.Refused).message)
    }

    @Test
    fun `survives a writer that throws`() {
        val result = DraftCommitMessageUseCase(
            CommitMessageWriter { throw RuntimeException("프로세스가 죽었다") },
        ).draft(state())

        assertEquals(DraftCommitMessageUseCase.FAILED, (result as CommitDraftResult.Refused).message)
    }

    @Test
    fun `a commit message is a prefix and a single line, nothing more`() {
        // 팀의 커밋은 `키 + type: 한 줄`이 전부다. 본문을 담을 자리도, 목록 밖 접두사도 없다.
        assertEquals("refactor: AI 코드 패키지 이관", WrittenCommitMessage(CommitType.REFACTOR, "AI 코드 패키지 이관").subject)
        assertEquals(listOf("feat", "fix", "docs", "style", "refactor", "test", "chore"), CommitType.PREFIXES)
        assertNull("목록 밖의 접두사는 값이 되지 못한다", CommitType.of("perf"))
        assertEquals(CommitType.FIX, CommitType.of(" FIX "))
    }
}
