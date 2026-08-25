package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.branch.GitRepositoryError
import io.github.gudwns1812.jiratask.domain.branch.GitRepositoryPort
import org.junit.Assert.assertEquals
import org.junit.Test

private class StartTaskFakeGitRepository(
    private val ensureRepositoryFailure: GitRepositoryError? = null,
) : GitRepositoryPort {
    var savedTaskNumber: String? = null
        private set

    override fun ensureRepository() {
        ensureRepositoryFailure?.let { throw it }
    }
    override fun ensureCleanWorkingTree() {}
    override fun ensureBranchAbsent(branchName: String) {}
    override fun createAndSwitch(branchName: String) {}
    override fun setCurrentTaskNumber(taskNumber: String) { savedTaskNumber = taskNumber }
    override fun getCurrentTaskNumber(): String? = savedTaskNumber
    override fun ensureStagedChanges() {}
    override fun commit(message: String) {}
}

class StartTaskTest {

    @Test
    fun `saves a valid task number`() {
        val git = StartTaskFakeGitRepository()

        val result = StartTaskUseCase(git).execute("257")

        assertEquals(StartTaskResult.Success("257"), result)
        assertEquals("257", git.savedTaskNumber)
    }

    @Test
    fun `rejects an invalid task number without touching git`() {
        val git = StartTaskFakeGitRepository()

        val result = StartTaskUseCase(git).execute("0") as StartTaskResult.Failure

        assertEquals(1, result.exitCode)
        assertEquals(null, git.savedTaskNumber)
    }

    @Test
    fun `fails with exit code 5 when not inside a git repository`() {
        val git = StartTaskFakeGitRepository(ensureRepositoryFailure = GitRepositoryError("Git 저장소에서 실행해야 합니다."))

        val result = StartTaskUseCase(git).execute("257") as StartTaskResult.Failure

        assertEquals(5, result.exitCode)
    }
}
