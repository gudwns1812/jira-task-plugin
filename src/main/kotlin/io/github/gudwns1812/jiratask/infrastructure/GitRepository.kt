package io.github.gudwns1812.jiratask.infrastructure

import io.github.gudwns1812.jiratask.domain.commit.CommitRepositoryPort
import io.github.gudwns1812.jiratask.domain.commit.GitRepositoryError
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * IDE의 Git4Idea 서비스 대신 `git` 실행 파일을 직접 호출한다. 하는 일이 rev-parse/config/commit 같은
 * 단순한 명령 조합뿐이라, Git4Idea의 무거운 캐시·리프레시 계층 없이도 같은 동작을 얻을 수 있다.
 */
class GitRepository(
    private val workingDirectory: File = File(System.getProperty("user.dir")),
) : CommitRepositoryPort {

    override fun ensureRepository() {
        val result = run("rev-parse", "--is-inside-work-tree")
        if (result == null || result.exitCode != 0 || result.stdout.trim() != "true") {
            throw GitRepositoryError("Git 저장소에서 실행해야 합니다.")
        }
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

    /** `origin` remote 주소. 없으면 null. MR을 만들 프로젝트를 여기서 알아낸다. */
    fun remoteUrl(remote: String = "origin"): String? {
        val result = run("remote", "get-url", remote) ?: return null
        if (result.exitCode != 0) {
            return null
        }
        return result.stdout.trim().ifEmpty { null }
    }

    /**
     * [base]에는 없고 현재 브랜치에만 있는 커밋 제목들. 오래된 것부터.
     * base를 모르거나 관계가 없으면 빈 목록 — MR을 못 만들 이유는 아니다.
     */
    fun commitSubjectsSince(base: String): List<String> {
        val result = run("log", "--format=%s", "--reverse", "$base..HEAD") ?: return emptyList()
        if (result.exitCode != 0) {
            return emptyList()
        }
        return result.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    /** 현재 브랜치가 원격에 올라가 있는가. 올라가지 않은 브랜치로는 MR을 만들 수 없다. */
    fun isPushed(branch: String, remote: String = "origin"): Boolean {
        val result = run("ls-remote", "--heads", remote, branch) ?: return false
        return result.exitCode == 0 && result.stdout.isNotBlank()
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
