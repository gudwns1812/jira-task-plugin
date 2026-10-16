package io.github.gudwns1812.jiratask.infrastructure.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Claude Code가 씌운 봉투에서 모델이 낸 글만 꺼낸다. stdout에는 봉투 말고 다른 줄이 섞여 나온다. */
class ClaudeCodeCliTest {

    @Test
    fun `takes the model's words out of the cli envelope`() {
        val envelope = """{"type":"result","result":"안녕","total_cost_usd":0.01}"""

        assertEquals("안녕", ClaudeCodeCli.textOf(envelope))
    }

    @Test
    fun `finds the envelope among lines that are not json`() {
        // 실제로 본 모양이다. MCP 서버 경고가 stdout에 함께 찍혀서 통째로 파싱하면 전부 실패한다.
        val noisy = """
            {"type":"result","result":"{\"title\":\"제목\"}","total_cost_usd":0.01}
            Client.listTools() called but server does not advertise tools capability - returning empty list
        """.trimIndent()

        assertEquals("""{"title":"제목"}""", ClaudeCodeCli.textOf(noisy))
        assertEquals("제목", AiDraftResponse.parse(ClaudeCodeCli.textOf(noisy))?.title)
    }

    @Test
    fun `finds the envelope even when the noise comes first`() {
        val noisy = "무언가 경고\n" + """{"result":"{\"title\":\"제목\"}"}"""

        assertEquals("""{"title":"제목"}""", ClaudeCodeCli.textOf(noisy))
    }

    @Test
    fun `passes the cli's own error message up`() {
        val envelope = """{"error":"Invalid API key · Please run /login"}"""

        assertTrue(ClaudeCodeCli.textOf(envelope)!!.contains("/login"))
    }

    @Test
    fun `treats output that is not an envelope as the words themselves`() {
        // CLI가 형식을 바꾸더라도 최악이 "못 읽음"이 되도록, 통째로 글로 보고 한 번 더 시도한다.
        assertEquals("""{"title":"제목"}""", ClaudeCodeCli.textOf("""{"title":"제목"}"""))
        assertEquals("그냥 글", ClaudeCodeCli.textOf("그냥 글"))
        assertNull(ClaudeCodeCli.textOf("   "))
    }

    @Test
    fun `calls the cli non-interactively and asks for the json envelope`() {
        val command = ClaudeCodeCli.invocationOf("claude.cmd", model = null).command

        assertEquals(listOf("claude.cmd", "-p", "--output-format", "json", "--restricted"), command)
    }

    @Test
    fun `names a model only when one was chosen`() {
        assertEquals(
            listOf("claude", "-p", "--output-format", "json", "--restricted", "--model", "opus"),
            ClaudeCodeCli.invocationOf("claude", "opus").command,
        )
        assertTrue(ClaudeCodeCli.invocationOf("claude", "  ").command.none { it == "--model" })
    }

    @Test
    fun `cuts the nested session variables but nothing else`() {
        assertTrue(ClaudeCodeCli.isSessionVariable("CLAUDECODE"))
        assertTrue(ClaudeCodeCli.isSessionVariable("CLAUDE_CODE_ENTRYPOINT"))
        assertEquals(false, ClaudeCodeCli.isSessionVariable("PATH"))
    }

    @Test
    fun `reads the model the user set in claude code's own settings`() {
        val home = Files.createTempDirectory("jira-task-claude-home-").toFile()
        try {
            File(home, ".claude").mkdirs()
            File(home, ".claude/settings.json").writeText("{ \"model\": \"claude-opus-5\" }")

            assertEquals("claude-opus-5", ClaudeCodeCli.configuredModel(home))
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `has no model to suggest when claude code was never configured`() {
        val home = Files.createTempDirectory("jira-task-claude-empty-").toFile()
        try {
            assertNull(ClaudeCodeCli.configuredModel(home))
        } finally {
            home.deleteRecursively()
        }
    }


    @Test
    fun `treats an envelope with no answer as a failure, not as text`() {
        // 맥에서 실제로 본 봉투다. 답이 비어 있는데 원본으로 되돌아가면 이 JSON이 통째로
        // 커밋 메시지 칸에 들어간다.
        val empty = """{"duration_api_ms":0,"stop_reason":"stop_sequence",""" +
            """"session_id":"c7ab41df","total_cost_usd":0,"usage":{"output_tokens":0}}"""

        assertNull(ClaudeCodeCli.textOf(empty))
    }

    @Test
    fun `treats an envelope whose result is blank as a failure`() {
        assertNull(ClaudeCodeCli.textOf("""{"session_id":"abc","result":"   "}"""))
    }

    @Test
    fun `still reads a plain answer that came without an envelope`() {
        // CLI가 형식을 바꾸더라도 최악이 "못 읽음"이 되도록, 봉투가 아니면 통째로 글로 본다.
        assertEquals("""{"subject":"제목"}""", ClaudeCodeCli.textOf("""{"subject":"제목"}"""))
    }


    @Test
    fun `passes the login failure the cli reported with exit code zero`() {
        // 맥에서 실제로 본 봉투다. 프로세스는 멀쩡히 끝나고 이유는 봉투 안에만 있다.
        val expired = """{"type":"result","subtype":"error_during_execution","is_error":true,""" +
            """"result":"Unable to authenticate: OAuth session expired and could not be refreshed",""" +
            """"session_id":"679fc054","duration_ms":60}"""

        val message = ClaudeCodeCli.errorOf(expired)

        assertTrue(message!!.contains("OAuth session expired"))
    }

    @Test
    fun `says nothing about failure when the answer came through`() {
        val fine = """{"type":"result","is_error":false,"session_id":"abc","result":"{}"}"""

        assertNull(ClaudeCodeCli.errorOf(fine))
        assertNull(ClaudeCodeCli.errorOf("그냥 글"))
    }

    @Test
    fun `still reports a failure the cli refused to explain`() {
        val silent = """{"type":"result","is_error":true,"session_id":"abc"}"""

        assertEquals(ClaudeCodeCli.FAILED_WITHOUT_REASON, ClaudeCodeCli.errorOf(silent))
    }


    @Test
    fun `tells the user to update when the cli does not know the option`() {
        // 맥에서 실제로 본 답이다: error: unknown option '--restricted'
        // 그 말을 그대로 올리면 사용자는 우리 코드가 잘못된 줄 안다. 할 일은 CLI를 올리는 것 하나다.
        val rejected = ProcessResult(1, "", "error: unknown option '--restricted'")

        assertEquals(ClaudeCodeCli.OUTDATED, ClaudeCodeCli.outdated(rejected))
    }

    @Test
    fun `keeps restricting the tools even on a version that refuses`() {
        // 도구를 막지 못한 채로 사용자 저장소에서 모델을 부르는 것은 초안 하나와 바꿀 일이 아니다.
        val command = ClaudeCodeCli.invocationOf("claude", null).command

        assertTrue(command.contains("--restricted"))
    }

    @Test
    fun `says nothing about the version when the failure was something else`() {
        assertNull(ClaudeCodeCli.outdated(ProcessResult(1, "", "Invalid API key · Please run /login")))
        assertNull(ClaudeCodeCli.outdated(ProcessResult(0, """{"result":"{}"}""", "")))
    }

}
