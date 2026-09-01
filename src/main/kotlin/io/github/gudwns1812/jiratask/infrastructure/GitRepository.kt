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
     * [target] 브랜치를 원격에서 가리키는 ref. MR 본문에 넣을 커밋을 어디서부터 셀지 정하는 값이다.
     *
     * `origin/main`을 가정하면 기본 브랜치가 `master`나 `develop`인 저장소에서 `git log`가 실패해
     * 커밋이 하나도 없는 것처럼 보이고, 커밋에서 뽑는 이슈 키도 함께 사라진다.
     * 그래서 실제로 있는 ref만 고른다 — 요청받은 브랜치, `origin/HEAD`가 가리키는 곳, 흔한 이름 순.
     */
    fun baseRef(target: String?, remote: String = "origin"): String? {
        val requested = target?.trim()?.ifEmpty { null }?.let { "$remote/${it.removePrefix("$remote/")}" }
        val candidates = listOfNotNull(requested, remoteHead(remote)) + FALLBACK_BRANCHES.map { "$remote/$it" }

        return candidates.firstOrNull(::hasRef)
    }

    /** `origin/HEAD`가 가리키는 브랜치. 클론이 아니라 `git init`으로 시작한 저장소에는 없다. */
    private fun remoteHead(remote: String): String? {
        val result = run("symbolic-ref", "--short", "refs/remotes/$remote/HEAD") ?: return null
        if (result.exitCode != 0) {
            return null
        }
        return result.stdout.trim().ifEmpty { null }
    }

    private fun hasRef(ref: String): Boolean {
        val result = run("rev-parse", "--verify", "--quiet", "$ref^{commit}") ?: return false
        return result.exitCode == 0
    }

    /**
     * 원격에 있는 브랜치 이름들(`origin/` 접두 없이). MR 대상으로 고를 수 있는 후보다.
     *
     * `origin/HEAD`는 브랜치가 아니라 다른 브랜치를 가리키는 표지라 뺀다.
     */
    fun remoteBranches(remote: String = "origin"): List<String> {
        val prefix = "refs/remotes/$remote/"
        // 짧은 이름이 아니라 전체 refname으로 거른다 — `origin/HEAD`의 짧은 이름은 `origin`이라
        // 이름만 보고는 브랜치와 구분되지 않는다.
        val result = run("for-each-ref", "--format=%(refname)", "refs/remotes/$remote") ?: return emptyList()
        if (result.exitCode != 0) {
            return emptyList()
        }

        return result.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .filter { it.isNotEmpty() && it != "HEAD" }
            .distinct()
            .toList()
    }

    /**
     * [base]에는 없고 현재 브랜치에만 있는 커밋 제목들. 오래된 것부터.
     * base를 모르거나 관계가 없으면 빈 목록 — MR을 못 만들 이유는 아니다.
     *
     * 머지 커밋은 뺀다. `Merge branch ... into ...`는 이 브랜치가 한 일이 아니라
     * 기준 브랜치가 지나온 자국이라, MR 본문에 적으면 읽는 사람을 방해한다.
     */
    fun commitSubjectsSince(base: String): List<String> {
        val result = run("log", "--format=%s", "--reverse", "--no-merges", "$base..HEAD") ?: return emptyList()
        if (result.exitCode != 0) {
            return emptyList()
        }
        return result.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    /**
     * 현재 브랜치가 원격에 올라가 있는가. **확인하지 못하면 null** — 올라가지 않은 것과 다르다.
     *
     * `ls-remote`는 네트워크가 끊겼거나 SSH 키가 없어도 실패한다. 그것을 "안 올렸다"로 읽으면
     * 이미 푸시한 브랜치에 대고 "먼저 푸시하세요"라고 말하게 된다(ARCHITECTURE.md 4장).
     */
    fun isPushed(branch: String, remote: String = "origin"): Boolean? {
        val result = run("ls-remote", "--heads", remote, branch) ?: return null
        if (result.exitCode != 0) {
            return null
        }
        return result.stdout.isNotBlank()
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

    private companion object {
        /** `origin/HEAD`가 없을 때 기본 브랜치일 법한 이름들. 앞에서부터 실제로 있는 것을 쓴다. */
        val FALLBACK_BRANCHES = listOf("main", "master", "develop")
    }
}
