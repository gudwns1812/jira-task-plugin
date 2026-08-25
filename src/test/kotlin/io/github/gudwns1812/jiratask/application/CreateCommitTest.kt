package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.commit.GitRepositoryError
import io.github.gudwns1812.jiratask.domain.commit.CommitRepositoryPort
import org.junit.Assert.assertEquals
import org.junit.Test

private class CreateCommitFakeGitRepository(
    private val currentTaskNumber: String? = "257",
    private val hasStagedChanges: Boolean = true,
) : CommitRepositoryPort {
    var committedMessage: String? = null
        private set

    override fun ensureRepository() {}
    override fun setCurrentTaskNumber(taskNumber: String) {}
    override fun getCurrentTaskNumber(): String? = currentTaskNumber
    override fun ensureStagedChanges() {
        if (!hasStagedChanges) throw GitRepositoryError("커밋할 스테이지된 변경 사항이 없습니다.")
    }
    override fun commit(message: String) { committedMessage = message }
}

class CreateCommitTest {

    @Test
    fun `builds the issue key from the saved task number and commits`() {
        val git = CreateCommitFakeGitRepository()

        val result = CreateCommitUseCase(git).execute(CreateCommitCommand("S15P11A108", "이메일 회원가입 화면 구현"))

        assertEquals(
            CreateCommitResult.Success("S15P11A108-257", "S15P11A108-257 이메일 회원가입 화면 구현"),
            result,
        )
        assertEquals("S15P11A108-257 이메일 회원가입 화면 구현", git.committedMessage)
    }

    @Test
    fun `fails when no task number was saved`() {
        val result = CreateCommitUseCase(CreateCommitFakeGitRepository(currentTaskNumber = null))
            .execute(CreateCommitCommand("S15P11A108", "메시지"))

        assertEquals(1, (result as CreateCommitResult.Failure).exitCode)
    }

    @Test
    fun `fails when there are no staged changes`() {
        val result = CreateCommitUseCase(CreateCommitFakeGitRepository(hasStagedChanges = false))
            .execute(CreateCommitCommand("S15P11A108", "메시지"))

        assertEquals(5, (result as CreateCommitResult.Failure).exitCode)
    }
}
