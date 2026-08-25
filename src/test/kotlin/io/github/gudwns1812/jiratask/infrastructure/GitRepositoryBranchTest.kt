package io.github.gudwns1812.jiratask.infrastructure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** 툴 윈도우 표시용으로 추가한 현재 브랜치 조회. */
class GitRepositoryBranchTest {

    @Test
    fun `reads the current branch of a repository`() {
        val directory = createRepository()
        try {
            val repository = GitRepository(directory)
            val initial = repository.currentBranch()
            assertEquals(runGit(directory, "branch", "--show-current").trim(), initial)

            repository.createAndSwitch("feat/100-signup")

            assertEquals("feat/100-signup", repository.currentBranch())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `returns null outside a git repository`() {
        val directory = Files.createTempDirectory("jira-task-plugin-nogit-").toFile()
        try {
            assertNull(GitRepository(directory).currentBranch())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `returns null in a repository without commits`() {
        val directory = Files.createTempDirectory("jira-task-plugin-unborn-").toFile()
        try {
            runGit(directory, "init")
            // 커밋이 없어도 git은 기본 브랜치 이름을 보고하므로, 빈 값이 아니면 그 이름이어야 한다.
            val branch = GitRepository(directory).currentBranch()
            val expected = runGit(directory, "branch", "--show-current").trim().ifEmpty { null }
            assertEquals(expected, branch)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun createRepository(): File {
        val directory = Files.createTempDirectory("jira-task-plugin-branch-").toFile()
        runGit(directory, "init")
        runGit(directory, "config", "user.email", "test@example.com")
        runGit(directory, "config", "user.name", "Test")
        File(directory, "README.md").writeText("test\n")
        runGit(directory, "add", "README.md")
        runGit(directory, "commit", "-m", "initial")
        return directory
    }

    private fun runGit(directory: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(directory).start()
        val stdout = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val stderr = process.errorStream.bufferedReader(Charsets.UTF_8).readText()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "git 명령이 시간 내에 끝나지 않았습니다." }
        check(process.exitValue() == 0) { "git ${args.joinToString(" ")} 실패: $stderr" }
        return stdout
    }
}
