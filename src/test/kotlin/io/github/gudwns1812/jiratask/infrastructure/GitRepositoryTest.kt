package io.github.gudwns1812.jiratask.infrastructure

import io.github.gudwns1812.jiratask.domain.branch.GitRepositoryError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

private fun runGit(directory: File, vararg args: String): String {
    val process = ProcessBuilder(listOf("git") + args).directory(directory).start()
    val stdout = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
    val stderr = process.errorStream.bufferedReader(Charsets.UTF_8).readText()
    check(process.waitFor(30, TimeUnit.SECONDS)) { "git 명령이 시간 내에 끝나지 않았습니다." }
    check(process.exitValue() == 0) { "git ${args.joinToString(" ")} 실패: $stderr" }
    return stdout
}

private fun createRepository(): File {
    val directory = Files.createTempDirectory("jira-task-plugin-git-").toFile()
    runGit(directory, "init")
    runGit(directory, "config", "user.email", "test@example.com")
    runGit(directory, "config", "user.name", "Test")
    File(directory, "README.md").writeText("test\n")
    runGit(directory, "add", "README.md")
    runGit(directory, "commit", "-m", "initial")
    return directory
}

class GitRepositoryTest {

    @Test
    fun `creates and switches to a new branch in a clean temporary repository`() {
        val directory = createRepository()
        try {
            val repository = GitRepository(directory)
            repository.ensureRepository()
            repository.ensureCleanWorkingTree()
            repository.ensureBranchAbsent("feat/100-signup")
            repository.createAndSwitch("feat/100-signup")

            assertEquals("feat/100-signup\n", runGit(directory, "branch", "--show-current"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `rejects a dirty working tree and a duplicate branch name`() {
        val directory = createRepository()
        try {
            val repository = GitRepository(directory)
            File(directory, "dirty.txt").writeText("dirty")
            assertThrows(GitRepositoryError::class.java) { repository.ensureCleanWorkingTree() }

            runGit(directory, "switch", "-c", "feat/100-signup")
            assertThrows(GitRepositoryError::class.java) { repository.ensureBranchAbsent("feat/100-signup") }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `saves and reads the current task number per repository`() {
        val directory = createRepository()
        try {
            val repository = GitRepository(directory)
            assertNull(repository.getCurrentTaskNumber())

            repository.setCurrentTaskNumber("105")
            assertEquals("105", repository.getCurrentTaskNumber())

            repository.setCurrentTaskNumber("210")
            assertEquals("210", repository.getCurrentTaskNumber())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `checks staged changes and commits`() {
        val directory = createRepository()
        try {
            val repository = GitRepository(directory)
            assertThrows(GitRepositoryError::class.java) { repository.ensureStagedChanges() }

            File(directory, "feature.txt").writeText("feature")
            runGit(directory, "add", "feature.txt")
            repository.ensureStagedChanges()

            repository.commit("S15P11A108-105 기능 추가")
            assertEquals("S15P11A108-105 기능 추가\n", runGit(directory, "log", "-1", "--pretty=%s"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `rejects operating outside a git repository`() {
        val directory = Files.createTempDirectory("jira-task-plugin-non-git-").toFile()
        try {
            val repository = GitRepository(directory)
            assertThrows(GitRepositoryError::class.java) { repository.ensureRepository() }
        } finally {
            directory.deleteRecursively()
        }
    }
}
