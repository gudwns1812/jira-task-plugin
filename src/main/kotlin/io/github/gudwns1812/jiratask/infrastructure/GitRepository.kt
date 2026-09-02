package io.github.gudwns1812.jiratask.infrastructure

import io.github.gudwns1812.jiratask.domain.commit.CommitRepositoryPort
import io.github.gudwns1812.jiratask.domain.commit.PendingChanges
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
     * 저장소가 들고 있는 MR 템플릿들. 이름 → 내용.
     *
     * GitLab이 정한 자리는 [TEMPLATE_DIRECTORY] 아래의 `.md` 파일들이다. 작업 트리에서 읽는다 —
     * 엄밀히는 기본 브랜치의 것이지만, 작업 브랜치는 거기서 갈라져 나왔으니 대개 같고,
     * 이번 브랜치에서 템플릿을 손봤다면 그 손본 것을 쓰는 편이 덜 놀랍다.
     */
    fun mergeRequestTemplates(): Map<String, String> {
        val directory = File(workingDirectory, TEMPLATE_DIRECTORY)
        val files = directory.listFiles { file -> file.isFile && file.extension.equals("md", ignoreCase = true) }
            ?: return emptyMap()

        return files.sortedBy { it.name.lowercase() }
            .mapNotNull { file ->
                val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()?.takeIf { it.isNotBlank() }
                text?.let { file.nameWithoutExtension to it }
            }
            .toMap()
    }

    /**
     * [base] 이후 이 브랜치가 건드린 파일들. **경로만 읽는다 — 내용은 읽지 않는다.**
     *
     * AI에게 무엇을 보낼지가 이 메서드에 걸려 있다. `--name-only`를 `-p`로 바꾸는 순간
     * 소스 코드가 밖으로 나가므로, 그 결정은 여기를 고치는 일로만 할 수 있어야 한다.
     */
    fun changedFilesSince(base: String): List<String> {
        val result = run("diff", "--name-only", "$base..HEAD") ?: return emptyList()
        if (result.exitCode != 0) {
            return emptyList()
        }
        return result.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
    }

    /**
     * 지금 커밋하려는 변경. 커밋 메시지 초안의 재료다.
     *
     * 스테이지된 것이 있으면 그것만 보고, 없으면 작업 트리를 본다 — **고친 파일과 새로 만든 파일
     * 둘 다.** IDE 커밋 창은 스테이징 없이 고른 파일을 그대로 커밋하고 새 파일도 거기서 함께 고른다.
     * `git diff`만 보면 새 파일이 통째로 빠져서, 커밋할 것이 있는데도 "변경이 없습니다"라고 말하게 되고
     * 사용자는 IDE 흐름에 없는 `git add`를 강요당한다.
     *
     * 새 파일은 **경로만** 담긴다. `git diff`는 추적되지 않는 파일의 내용을 내지 않고, 그 내용을
     * 굳이 따로 읽지도 않는다 — 보내는 것을 늘리는 결정은 이 메서드를 고치는 일로만 할 수 있어야 한다.
     *
     * **[includeDiff]가 false면 내용을 아예 읽지 않는다.** 설정에서 diff 전송을 껐다는 뜻이므로,
     * 보내지 않을 것을 굳이 프로세스로 꺼내 오지 않는다. AI에게 무엇이 나가는지가 이 분기에 걸려 있다.
     */
    fun pendingChanges(includeDiff: Boolean = false): PendingChanges {
        val staged = linesOf("diff", "--cached", "--name-only")
        if (staged.isNotEmpty()) {
            return PendingChanges(staged, diffOf(includeDiff, "--cached"), staged = true)
        }

        val modified = linesOf("diff", "--name-only", "HEAD")
        val created = linesOf("ls-files", "--others", "--exclude-standard")
        return PendingChanges(
            files = (modified + created).distinct(),
            diff = diffOf(includeDiff, "HEAD"),
            staged = false,
        )
    }

    private fun diffOf(includeDiff: Boolean, vararg range: String): String {
        if (!includeDiff) {
            return ""
        }
        val result = run(*QUOTE_PATH_OFF, "diff", "--no-color", *range) ?: return ""
        return if (result.exitCode == 0) result.stdout else ""
    }

    private fun linesOf(vararg args: String): List<String> {
        // 한글 파일 이름을 8진 이스케이프로 뱉지 않게 한다. 그렇게 나온 경로는 AI에게도,
        // 사람에게도 읽히지 않는다.
        val result = run(*QUOTE_PATH_OFF, *args) ?: return emptyList()
        if (result.exitCode != 0) {
            return emptyList()
        }
        return result.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
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

        /** GitLab이 MR 템플릿을 찾는 자리. */
        const val TEMPLATE_DIRECTORY = ".gitlab/merge_request_templates"

        /** 경로를 8진 이스케이프로 바꾸지 말라는 옵션. 명령 앞에 붙인다. */
        val QUOTE_PATH_OFF = arrayOf("-c", "core.quotepath=false")
    }
}
