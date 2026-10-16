package io.github.gudwns1812.jiratask.infrastructure.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * CLI를 부르고 답을 읽는 전 과정.
 *
 * 프로세스를 띄우는 자리를 이음매로 열어 둔 덕에 **CLI를 깔지 않고** 확인한다. 그 자리가 막혀
 * 있으면 "로그인이 필요하다", "사용량 한도" 같은 실패가 화면에 어떻게 올라가는지 아무도 볼 수 없다.
 */
class AiCliRunnerTest {

    private val calls = mutableListOf<List<String>>()
    private var input: String? = null

    @Before
    fun clearCache() {
        // 찾아 둔 자리를 세션 동안 기억하므로, 테스트끼리 서로의 답을 물려받지 않게 지운다.
        AiCliLocations.forget()
    }

    private fun runner(
        cli: AiCli = ClaudeCodeCli,
        executablePath: String? = "/usr/local/bin/claude",
        model: String? = null,
        answer: (List<String>) -> ProcessResult? = { ProcessResult(0, "", "") },
    ) = AiCliRunner(
        cli = cli,
        workingDirectory = File("."),
        executablePath = executablePath,
        model = model,
        os = OperatingSystem.MAC,
        run = { command, stdin, _ ->
            calls += command
            stdin?.let { input = it }
            answer(command)
        },
    )

    private fun said(text: String): (List<String>) -> ProcessResult = { command ->
        if (command.contains("--version")) ProcessResult(0, "2.1.0", "") else ProcessResult(0, text, "")
    }

    @Test
    fun `hands the model's words back once the envelope is off`() {
        val answer = runner(answer = said("""{"type":"result","result":"{\"title\":\"제목\"}"}"""))
            .ask("초안을 써 주세요")

        assertEquals(AiAnswer.Said("""{"title":"제목"}"""), answer)
    }

    @Test
    fun `sends the prompt on stdin, never as an argument`() {
        // Windows에서 인자 안의 줄바꿈이 명령줄을 끊는다. 프롬프트가 인자로 새면 그 자리에서 깨진다.
        runner(answer = said("""{"result":"{}"}""")).ask("여러 줄\n프롬프트")

        assertEquals("여러 줄\n프롬프트", input)
        assertTrue(calls.none { command -> command.any { it.contains("여러 줄") } })
    }

    @Test
    fun `asks the cli about its own model only when one was chosen`() {
        runner(model = "opus", answer = said("""{"result":"{}"}""")).ask("무엇이든")
        assertTrue(calls.last().containsAll(listOf("--model", "opus")))

        calls.clear()
        runner(model = "   ", answer = said("""{"result":"{}"}""")).ask("무엇이든")
        assertTrue("빈 모델은 붙이지 않는다 — 사용자가 CLI에 정해 둔 것을 따른다", calls.last().none { it == "--model" })
    }

    @Test
    fun `passes the cli's own complaint up when it fails`() {
        // 우리가 지어낸 문구보다 저쪽 설명이 늘 낫다. "로그인하세요"는 저쪽만 말해 줄 수 있다.
        val answer = runner(answer = { command ->
            if (command.contains("--version")) {
                ProcessResult(0, "2.1.0", "")
            } else {
                ProcessResult(1, "", "Invalid API key · Please run /login\n두 번째 줄")
            }
        }).ask("무엇이든")

        assertEquals(AiAnswer.Failed("Invalid API key · Please run /login"), answer)
    }

    @Test
    fun `calls it a timeout when the process never comes back`() {
        val answer = runner(answer = { command ->
            if (command.contains("--version")) ProcessResult(0, "2.1.0", "") else null
        }).ask("무엇이든")

        assertTrue(answer is AiAnswer.Failed)
        assertTrue((answer as AiAnswer.Failed).message.contains("제때 답하지 않았습니다"))
    }

    @Test
    fun `says the answer was unreadable rather than pretending it wrote something`() {
        val answer = runner(answer = said("   ")).ask("무엇이든")

        assertEquals(AiAnswer.Failed(AiCliRunner.unreadable(ClaudeCodeCli)), answer)
    }

    @Test
    fun `blames the path the user typed, not the installation`() {
        // 사용자가 직접 적어 넣은 값이 문제인데 "설치하세요"라고 하면 엉뚱한 자리를 뒤지게 된다.
        val answer = runner(executablePath = "C:/틀린/claude.cmd", answer = { null }).ask("무엇이든")

        assertTrue(answer is AiAnswer.NotAvailable)
        assertTrue((answer as AiAnswer.NotAvailable).message.contains("C:/틀린/claude.cmd"))
    }

    @Test
    fun `tells the user how to install it when there is nothing to call`() {
        val answer = runner(executablePath = null, answer = { null }).ask("무엇이든")

        assertTrue(answer is AiAnswer.NotAvailable)
        assertTrue((answer as AiAnswer.NotAvailable).message.contains("npm install -g @anthropic-ai/claude-code"))
    }

    @Test
    fun `always tidies up after a call, even one that failed`() {
        // Codex는 답을 임시 파일로 받는다. 실패했을 때 치우지 않으면 초안을 만들 때마다 쓰레기가 쌓인다.
        var tidied = 0
        val leaky = object : AiCli by ClaudeCodeCli {
            override fun invocationOf(executable: String, model: String?): Invocation = Invocation(
                command = listOf(executable, "--go"),
                readText = { null },
                cleanUp = { tidied++ },
            )
        }

        // 부를 수는 있는데 호출이 실패한 경우다 — 그때도 임시 파일은 남으면 안 된다.
        runner(cli = leaky, answer = { command -> if (command.contains("--version")) ProcessResult(0, "", "") else null })
            .ask("무엇이든")

        assertEquals(1, tidied)
    }
}
