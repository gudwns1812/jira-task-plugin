package io.github.gudwns1812.jiratask.infrastructure

import io.github.gudwns1812.jiratask.domain.commit.GitRepositoryError
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
    fun `prefers the requested target branch when the remote has it`() {
        val directory = createRepository()
        try {
            runGit(directory, "update-ref", "refs/remotes/origin/develop", "HEAD")
            runGit(directory, "update-ref", "refs/remotes/origin/main", "HEAD")

            assertEquals("origin/develop", GitRepository(directory).baseRef("develop"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `follows origin HEAD when the requested branch is not on the remote`() {
        val directory = createRepository()
        try {
            runGit(directory, "update-ref", "refs/remotes/origin/master", "HEAD")
            runGit(directory, "symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/master")

            // `main`을 물어도 없는 ref를 돌려주면 `git log`가 실패해 커밋이 통째로 비어 버린다.
            assertEquals("origin/master", GitRepository(directory).baseRef("main"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `falls back to a common default branch when origin HEAD is missing`() {
        val directory = createRepository()
        try {
            runGit(directory, "update-ref", "refs/remotes/origin/master", "HEAD")

            assertEquals("origin/master", GitRepository(directory).baseRef(null))
            assertEquals("origin/master", GitRepository(directory).baseRef("  "))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `finds no base when the repository has no remote branches`() {
        val directory = createRepository()
        try {
            assertNull(GitRepository(directory).baseRef("main"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `lists the remote branches that can be merge targets`() {
        val directory = createRepository()
        try {
            runGit(directory, "update-ref", "refs/remotes/origin/main", "HEAD")
            runGit(directory, "update-ref", "refs/remotes/origin/backend-dev", "HEAD")
            runGit(directory, "symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main")

            // origin/HEAD는 브랜치가 아니라 다른 브랜치를 가리키는 표지다. 짧은 이름이 `origin`이라
            // 이름만 보고 거르면 목록에 섞여 들어온다.
            assertEquals(listOf("backend-dev", "main"), GitRepository(directory).remoteBranches().sorted())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `leaves merge commits out of the branch commits`() {
        val directory = createRepository()
        try {
            runGit(directory, "update-ref", "refs/remotes/origin/backend-dev", "HEAD")
            runGit(directory, "checkout", "-b", "side")
            File(directory, "side.txt").writeText("side")
            runGit(directory, "add", "side.txt")
            runGit(directory, "commit", "-m", "S15P11A601-266 곁가지")

            runGit(directory, "checkout", "-b", "feat/mine", "HEAD~1")
            File(directory, "mine.txt").writeText("mine")
            runGit(directory, "add", "mine.txt")
            runGit(directory, "commit", "-m", "S15P11A601-267 내 작업")
            runGit(directory, "merge", "--no-ff", "-m", "Merge branch 'side' into 'feat/mine'", "side")

            val subjects = GitRepository(directory).commitSubjectsSince("origin/backend-dev")

            assertEquals(listOf("S15P11A601-266 곁가지", "S15P11A601-267 내 작업"), subjects.sorted())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `returns no commits for a base that does not exist`() {
        val directory = createRepository()
        try {
            assertEquals(emptyList<String>(), GitRepository(directory).commitSubjectsSince("origin/nope"))
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
