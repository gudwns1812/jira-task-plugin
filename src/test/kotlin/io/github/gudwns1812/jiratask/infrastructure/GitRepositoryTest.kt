package io.github.gudwns1812.jiratask.infrastructure

import io.github.gudwns1812.jiratask.domain.commit.GitRepositoryError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
    fun `accepts a target that already names the remote`() {
        val directory = createRepository()
        try {
            runGit(directory, "update-ref", "refs/remotes/origin/develop", "HEAD")

            assertEquals("origin/develop", GitRepository(directory).baseRef("origin/develop"))
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
    fun `lists only the commits this branch added on top of the base`() {
        val directory = createRepository()
        try {
            runGit(directory, "update-ref", "refs/remotes/origin/main", "HEAD")
            runGit(directory, "checkout", "-b", "feat/signup")
            File(directory, "signup.txt").writeText("signup")
            runGit(directory, "add", "signup.txt")
            runGit(directory, "commit", "-m", "S15P11A108-257 회원가입")

            val repository = GitRepository(directory)
            val base = repository.baseRef("main")

            assertEquals("origin/main", base)
            assertEquals(listOf("S15P11A108-257 회원가입"), repository.commitSubjectsSince(base!!))
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

            // origin/HEAD는 브랜치가 아니라 다른 브랜치를 가리키는 표지다.
            assertEquals(listOf("backend-dev", "main"), GitRepository(directory).remoteBranches().sorted())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `lists no remote branches when the repository has none`() {
        val directory = createRepository()
        try {
            assertEquals(emptyList<String>(), GitRepository(directory).remoteBranches())
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
    fun `lists the paths this branch touched, never their contents`() {
        val directory = createRepository()
        try {
            runGit(directory, "update-ref", "refs/remotes/origin/main", "HEAD")
            runGit(directory, "checkout", "-b", "feat/signup")
            File(directory, "signup.txt").writeText("비밀번호 = 1234")
            File(directory, "README.md").writeText("고침\n")
            runGit(directory, "add", ".")
            runGit(directory, "commit", "-m", "S15P11A108-257 회원가입")

            val changed = GitRepository(directory).changedFilesSince("origin/main")

            assertEquals(listOf("README.md", "signup.txt"), changed.sorted())
            // 경로만 읽는다. 이 단언이 깨지면 파일 내용을 읽기 시작했다는 뜻이다.
            assertTrue("내용은 읽지 않는다", changed.none { it.contains("비밀번호") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `returns no changed files for a base that does not exist`() {
        val directory = createRepository()
        try {
            assertEquals(emptyList<String>(), GitRepository(directory).changedFilesSince("origin/nope"))
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
    fun `cannot tell whether a branch is pushed when there is no remote`() {
        val directory = createRepository()
        try {
            // origin이 없으면 ls-remote가 실패한다. 그것은 "안 올렸다"가 아니라 "모른다"이다.
            assertNull(GitRepository(directory).isPushed("feat/signup"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `reads the merge request templates the repository keeps`() {
        val directory = createRepository()
        try {
            // GitLab이 정한 자리다. 경로를 바꾸면 팀 템플릿을 못 찾는다.
            val templates = File(directory, ".gitlab/merge_request_templates").apply { mkdirs() }
            File(templates, "Default.md").writeText("## 관련 이슈")
            File(templates, "Bugfix.md").writeText("## 재현 방법")
            File(templates, "README.txt").writeText("템플릿이 아니다")
            File(templates, "Empty.md").writeText("   ")

            val read = GitRepository(directory).mergeRequestTemplates()

            // 이름은 확장자를 뗀 것이고, .md가 아니거나 빈 파일은 담지 않는다.
            assertEquals(listOf("Bugfix", "Default"), read.keys.toList().sorted())
            assertTrue(read.getValue("Default").contains("관련 이슈"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `finds the templates from a subdirectory of the repository`() {
        val directory = createRepository()
        try {
            // IDE가 `proj/backend`만 열어 두는 일이 흔하다. 템플릿은 저장소 뿌리에만 있다.
            val templates = File(directory, ".gitlab/merge_request_templates").apply { mkdirs() }
            File(templates, "Default.md").writeText("## 관련 이슈")
            val module = File(directory, "backend/something").apply { mkdirs() }

            val read = GitRepository(module).mergeRequestTemplates()

            assertEquals(listOf("Default"), read.keys.toList())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `has no templates when the repository keeps none`() {
        val directory = createRepository()
        try {
            assertEquals(emptyMap<String, String>(), GitRepository(directory).mergeRequestTemplates())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `reads what is staged for the next commit`() {
        val directory = createRepository()
        try {
            File(directory, "feature.txt").writeText("feature\n")
            runGit(directory, "add", "feature.txt")

            val changes = GitRepository(directory).pendingChanges(includeDiff = true)

            assertTrue(changes.staged)
            assertEquals(listOf("feature.txt"), changes.files)
            assertTrue(changes.diff.contains("+feature"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `falls back to the working tree when nothing is staged`() {
        // IDE 커밋 창은 스테이징 없이 고른 파일을 커밋한다. 스테이지만 보면 재료가 있는데도 없다고 한다.
        val directory = createRepository()
        try {
            File(directory, "README.md").writeText("test\n고친 줄\n")

            val changes = GitRepository(directory).pendingChanges(includeDiff = true)

            assertFalse(changes.staged)
            assertEquals(listOf("README.md"), changes.files)
            assertTrue(changes.diff.contains("+고친 줄"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `reads the paths but never the contents when the diff is turned off`() {
        // AI에게 소스 코드가 나가는지가 이 분기에 걸려 있다.
        val directory = createRepository()
        try {
            File(directory, "secret.txt").writeText("비밀 값\n")
            runGit(directory, "add", "secret.txt")

            val changes = GitRepository(directory).pendingChanges(includeDiff = false)

            assertEquals(listOf("secret.txt"), changes.files)
            assertEquals("", changes.diff)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `sees a brand new file that was never added`() {
        // IDE 커밋 창은 새 파일도 그냥 골라서 커밋한다. git add를 요구하는 것은 그 흐름이 아니다.
        val directory = createRepository()
        try {
            File(directory, "새 화면.kt").writeText("val secret = 1\n")

            val changes = GitRepository(directory).pendingChanges(includeDiff = true)

            assertEquals(listOf("새 화면.kt"), changes.files)
            assertFalse(changes.staged)
            assertFalse("새 파일의 내용은 diff에 담기지 않는다", changes.diff.contains("val secret"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `sees a changed file and a new file together`() {
        val directory = createRepository()
        try {
            File(directory, "README.md").writeText("test\n고친 줄\n")
            File(directory, "added.txt").writeText("new\n")

            val changes = GitRepository(directory).pendingChanges(includeDiff = true)

            assertEquals(listOf("README.md", "added.txt"), changes.files)
            assertTrue(changes.diff.contains("+고친 줄"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `has nothing pending in a clean repository`() {
        val directory = createRepository()
        try {
            val changes = GitRepository(directory).pendingChanges(includeDiff = true)

            assertTrue(changes.files.isEmpty())
            assertEquals("", changes.diff)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `has no templates to offer outside a git repository`() {
        // 뿌리를 못 물으면 열어 둔 폴더를 그대로 본다. 거기에도 없으면 빈 목록이다.
        val directory = Files.createTempDirectory("jira-task-plugin-non-git-").toFile()
        try {
            assertEquals(emptyMap<String, String>(), GitRepository(directory).mergeRequestTemplates())
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
