package io.github.gudwns1812.jiratask.infrastructure.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Codex를 부르는 방법.
 *
 * 명령줄을 그대로 시험한다 — 플래그 하나가 틀리면 기능 전체가 서지 않는데, 그 사실을 실제로 부르기
 * 전까지는 알 수 없기 때문이다.
 */
class CodexCliTest {

    private fun commandOf(model: String? = null): List<String> =
        CodexCli.invocationOf("codex", model).also { it.cleanUp() }.command

    @Test
    fun `runs the non-interactive subcommand and reads the prompt from stdin`() {
        val command = commandOf()

        assertEquals(listOf("codex", "exec"), command.take(2))
        assertEquals("stdin에서 읽으라는 표시는 맨 뒤에 둔다", "-", command.last())
        assertTrue(command.contains(CodexCli.SKIP_GIT_REPO_CHECK))
    }

    @Test
    fun `asks for the answer in a file instead of parsing the event stream`() {
        // `--json`의 이벤트 이름은 버전마다 움직인다. 최종 답만 파일로 받는 쪽이 훨씬 덜 깨진다.
        val command = commandOf()
        val at = command.indexOf(CodexCli.OUTPUT_LAST_MESSAGE)

        assertTrue(at > 0)
        assertTrue(command[at + 1].endsWith(".txt"))
        assertFalse(command.contains("--json"))
    }

    @Test
    fun `keeps it read-only because it is only being asked to write words`() {
        assertTrue(commandOf().containsAll(CodexCli.SANDBOX))
    }

    @Test
    fun `names a model only when one was chosen`() {
        assertTrue(commandOf("gpt-5-codex").containsAll(listOf("--model", "gpt-5-codex")))
        assertFalse(commandOf("  ").contains("--model"))
        assertFalse(commandOf(null).contains("--model"))
    }

    @Test
    fun `suggests every current GPT 5 6 Codex model tier`() {
        assertEquals(
            listOf("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna", "gpt-5.6"),
            CodexCli.models,
        )
    }

    @Test
    fun `reads the answer out of the file it asked for`() {
        val file = File.createTempFile("jira-task-codex-test", ".txt")
        file.writeText("""{"title":"제목"}""")

        assertEquals("""{"title":"제목"}""", CodexCli.textOf(file, stdout = "진행 로그"))
        file.delete()
    }

    @Test
    fun `falls back to stdout when the file stayed empty`() {
        // 그 플래그가 없는 낡은 버전을 만나도 최악이 "못 읽음"이 되지 않게 한 번 더 시도한다.
        val empty = File.createTempFile("jira-task-codex-test", ".txt")

        val noisy = "thinking...\n{\"type\":\"feat\",\"summary\":\"회원가입 폼 구현\"}\ndone in 3.2s"
        assertEquals("""{"type":"feat","summary":"회원가입 폼 구현"}""", CodexCli.textOf(empty, noisy))

        assertNull(CodexCli.textOf(empty, "아무 JSON도 없는 로그"))
        empty.delete()
    }

    @Test
    fun `deletes the file it made`() {
        val invocation = CodexCli.invocationOf("codex", null)
        val file = File(invocation.command[invocation.command.indexOf(CodexCli.OUTPUT_LAST_MESSAGE) + 1])
        assertTrue(file.exists())

        invocation.cleanUp()

        assertFalse("임시 파일을 남기지 않는다", file.exists())
    }

    @Test
    fun `never scrubs the variable that holds the login`() {
        // CODEX_HOME 안에 로그인 정보가 있다. 지우면 구독 인증을 잃는다.
        assertFalse(CodexCli.isSessionVariable("CODEX_HOME"))
        assertTrue(CodexCli.isSessionVariable("CODEX_SANDBOX"))
        assertTrue(CodexCli.isSessionVariable("CODEX_SANDBOX_NETWORK_DISABLED"))
    }

    @Test
    fun `reads the model the user set in codex's own config`() {
        // 어떤 모델이 있는지 물어볼 곳이 없다. 사용자가 이미 고른 것이 우리가 아는 유일한 진짜 이름이다.
        val home = Files.createTempDirectory("jira-task-codex-home-").toFile()
        try {
            File(home, ".codex").mkdirs()
            File(home, ".codex/config.toml")
                .writeText("model = \"gpt-5.6-terra\"" + System.lineSeparator() + "model_reasoning_effort = \"high\"")

            assertEquals("gpt-5.6-terra", CodexCli.configuredModel(home))
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `has no model to suggest when codex was never configured`() {
        val home = Files.createTempDirectory("jira-task-codex-empty-").toFile()
        try {
            assertNull(CodexCli.configuredModel(home))
        } finally {
            home.deleteRecursively()
        }
    }

}
