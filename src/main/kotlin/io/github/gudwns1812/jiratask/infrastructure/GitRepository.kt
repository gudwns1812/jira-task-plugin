package io.github.gudwns1812.jiratask.infrastructure

import io.github.gudwns1812.jiratask.domain.commit.CommitRepositoryPort
import io.github.gudwns1812.jiratask.domain.commit.PendingChanges
import io.github.gudwns1812.jiratask.domain.commit.GitRepositoryError
import java.io.File
import java.util.concurrent.TimeUnit

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

    fun currentBranch(): String? {
        val result = run("branch", "--show-current") ?: return null
        if (result.exitCode != 0) {
            return null
        }
        return result.stdout.trim().ifEmpty { null }
    }

    fun remoteUrl(remote: String = "origin"): String? {
        val result = run("remote", "get-url", remote) ?: return null
        if (result.exitCode != 0) {
            return null
        }
        return result.stdout.trim().ifEmpty { null }
    }

    fun baseRef(target: String?, remote: String = "origin"): String? {
        val requested = target?.trim()?.ifEmpty { null }?.let { "$remote/${it.removePrefix("$remote/")}" }
        val candidates = listOfNotNull(requested, remoteHead(remote)) + FALLBACK_BRANCHES.map { "$remote/$it" }

        return candidates.firstOrNull(::hasRef)
    }

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

    fun remoteBranches(remote: String = "origin"): List<String> {
        val prefix = "refs/remotes/$remote/"
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

    fun commitSubjectsSince(base: String): List<String> {
        val result = run("log", "--format=%s", "--reverse", "--no-merges", "$base..HEAD") ?: return emptyList()
        if (result.exitCode != 0) {
            return emptyList()
        }
        return result.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    private fun repositoryRoot(): File {
        val result = run("rev-parse", "--show-toplevel") ?: return workingDirectory
        if (result.exitCode != 0) {
            return workingDirectory
        }
        return result.stdout.trim().ifEmpty { null }?.let(::File) ?: workingDirectory
    }

    fun mergeRequestTemplates(): Map<String, String> {
        val directory = File(repositoryRoot(), TEMPLATE_DIRECTORY)
        val files = directory.listFiles { file -> file.isFile && file.extension.equals("md", ignoreCase = true) }
            ?: return emptyMap()

        return files.sortedBy { it.name.lowercase() }
            .mapNotNull { file ->
                val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()?.takeIf { it.isNotBlank() }
                text?.let { file.nameWithoutExtension to it }
            }
            .toMap()
    }

    fun changedFilesSince(base: String): List<String> {
        val result = run("diff", "--name-only", "$base..HEAD") ?: return emptyList()
        if (result.exitCode != 0) {
            return emptyList()
        }
        return result.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
    }

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
        val result = run(*QUOTE_PATH_OFF, *args) ?: return emptyList()
        if (result.exitCode != 0) {
            return emptyList()
        }
        return result.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
    }

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
        val FALLBACK_BRANCHES = listOf("main", "master", "develop")

        const val TEMPLATE_DIRECTORY = ".gitlab/merge_request_templates"

        val QUOTE_PATH_OFF = arrayOf("-c", "core.quotepath=false")
    }
}
