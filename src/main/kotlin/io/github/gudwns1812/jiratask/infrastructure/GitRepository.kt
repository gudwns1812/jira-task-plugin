package io.github.gudwns1812.jiratask.infrastructure

import io.github.gudwns1812.jiratask.domain.branch.GitRepositoryError
import io.github.gudwns1812.jiratask.domain.branch.GitRepositoryPort
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * IDE의 Git4Idea 서비스 대신 CLI와 동일하게 `git` 실행 파일을 직접 호출한다.
 * CLI(`src/infrastructure/git-repository.ts`)가 하는 일이 rev-parse/status/switch/commit 같은
 * 단순한 명령 조합뿐이라, Git4Idea의 무거운 캐시·리프레시 계층 없이도 동일한 동작을 재현할 수 있다.
 */
class GitRepository(
    private val workingDirectory: File = File(System.getProperty("user.dir")),
) : GitRepositoryPort {

    override fun ensureRepository() {
        val result = run("rev-parse", "--is-inside-work-tree")
        if (result == null || result.exitCode != 0 || result.stdout.trim() != "true") {
            throw GitRepositoryError("Git 저장소에서 실행해야 합니다.")
        }
    }

    override fun ensureCleanWorkingTree() {
        val result = runOrThrow("status", "--porcelain")
        if (result.stdout.trim().isNotEmpty()) {
            throw GitRepositoryError("커밋되지 않은 변경 사항이 있어 브랜치를 만들 수 없습니다.")
        }
    }

    override fun ensureBranchAbsent(branchName: String) {
        val result = runOrThrow("for-each-ref", "--format=%(refname:short)", "refs/heads", "refs/remotes")
        val existing = result.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any { it == branchName || it.endsWith("/$branchName") }

        if (existing) {
            throw GitRepositoryError("이미 존재하는 브랜치입니다: $branchName")
        }
    }

    override fun createAndSwitch(branchName: String) {
        runOrThrow("switch", "-c", branchName)
    }

    override fun setCurrentTaskNumber(taskNumber: String) {
        runOrThrow("config", "--local", "jira-task.current-task", taskNumber)
    }

    override fun getCurrentTaskNumber(): String? {
        val result = run("config", "--local", "--get", "jira-task.current-task")
            ?: throw GitRepositoryError("Git 명령을 실행하지 못했습니다.")

        if (result.exitCode == 1) {
            return null
        }
        if (result.exitCode != 0) {
            throw GitRepositoryError("Git 명령을 실행하지 못했습니다.")
        }

        val taskNumber = result.stdout.trim()
        return taskNumber.ifEmpty { null }
    }

    override fun ensureStagedChanges() {
        val result = runOrThrow("diff", "--cached", "--name-only")
        if (result.stdout.trim().isEmpty()) {
            throw GitRepositoryError("커밋할 스테이지된 변경 사항이 없습니다.")
        }
    }

    override fun commit(message: String) {
        runOrThrow("commit", "-m", message)
    }

    private fun runOrThrow(vararg args: String): ProcessResult {
        val result = run(*args) ?: throw GitRepositoryError("Git 명령을 실행하지 못했습니다.")
        if (result.exitCode != 0) {
            throw GitRepositoryError("Git 명령을 실행하지 못했습니다.")
        }
        return result
    }

    /**
     * 현재 브랜치 이름. 툴 윈도우 표시용으로만 쓰는 읽기 전용 정보이므로 도메인 포트에는 두지 않았다.
     * 저장소가 아니거나 커밋이 없는 상태(detached/unborn)면 null.
     */
    fun currentBranch(): String? {
        val result = run("branch", "--show-current") ?: return null
        if (result.exitCode != 0) {
            return null
        }
        return result.stdout.trim().ifEmpty { null }
    }

    private fun run(vararg args: String): ProcessResult? = try {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(workingDirectory)
            .start()
        val stdout = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        process.errorStream.bufferedReader(Charsets.UTF_8).readText()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (finished) ProcessResult(process.exitValue(), stdout) else null.also { process.destroyForcibly() }
    } catch (error: Exception) {
        null
    }

    private data class ProcessResult(val exitCode: Int, val stdout: String)
}
