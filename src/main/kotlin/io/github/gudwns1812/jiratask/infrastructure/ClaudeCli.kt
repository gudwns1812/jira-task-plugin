package io.github.gudwns1812.jiratask.infrastructure

import java.io.File
import java.util.concurrent.TimeUnit

/** Claude Code에게 물어본 결과. 무엇을 쓰려고 물었는지는 이 타입이 알지 않는다. */
sealed interface ClaudeAnswer {

    /** 모델이 낸 글. CLI 봉투는 이미 벗겨 낸 상태다. */
    data class Said(val text: String) : ClaudeAnswer

    /** 애초에 부를 수 없다 — 설치되지 않았거나 경로가 틀렸다. */
    data class NotAvailable(val message: String) : ClaudeAnswer

    /** 불렀는데 잘 되지 않았다 — 시한 초과, 사용량 한도, 로그인 필요. */
    data class Failed(val message: String) : ClaudeAnswer
}

/**
 * 사용자가 설치하고 로그인해 둔 **Claude Code CLI**를 비대화형으로 부른다.
 *
 * API 키를 쓰지 않는 이유는 요금 때문이다. Messages API는 언제나 API 키로 사용량 과금되고
 * Pro/Max 구독은 그것을 덮지 않는다. 구독이 덮는 것은 claude.ai와 Claude Code라,
 * 구독으로 쓰려면 사용자의 Claude Code 로그인을 그대로 타는 이 길뿐이다.
 * **플러그인은 어떤 자격 증명도 갖지 않는다.**
 *
 * MR 초안과 커밋 초안이 함께 쓴다 — 아래 [run]이 다루는 문제(스트림 교착, 시한, 중첩 세션)는
 * 무엇을 쓰게 하든 똑같이 생기므로 한 자리에만 둔다.
 */
class ClaudeCli(
    private val workingDirectory: File,
    /** 설정에서 받은 실행 파일 경로. 비어 있으면 PATH에서 찾는다. */
    private val executablePath: String? = null,
    /** 설정에서 받은 모델. **비어 있으면 붙이지 않는다** — 사용자가 Claude Code에 정해 둔 것을 따른다. */
    private val model: String? = null,
    private val timeout: Long = DEFAULT_TIMEOUT_SECONDS,
) {

    /** [prompt]를 stdin으로 넘기고 모델이 낸 글을 받아 온다. 몇십 초가 걸린다 — 백그라운드에서만 부른다. */
    fun ask(prompt: String): ClaudeAnswer {
        val executable = resolveExecutable()
            ?: return ClaudeAnswer.NotAvailable(NOT_FOUND)

        // 프롬프트는 인자가 아니라 stdin으로 넘긴다 — 이유는 [ClaudeDraftPrompt.stdinOf]에 적어 두었다.
        val command = buildList {
            add(executable)
            add("-p")
            add("--output-format")
            add("json")
            // 글만 써 달라는 일이라 명령 실행 도구와 WebFetch는 필요 없다.
            add("--restricted")
            model?.trim()?.takeIf { it.isNotEmpty() }?.let {
                add("--model")
                add(it)
            }
        }

        val result = run(command, input = prompt)
            ?: return ClaudeAnswer.Failed(TIMED_OUT)
        if (result.exitCode != 0) {
            return ClaudeAnswer.Failed(result.complaint())
        }

        return ClaudeDraftResponse.textOf(result.stdout)?.let(ClaudeAnswer::Said)
            ?: ClaudeAnswer.Failed(UNREADABLE)
    }

    /**
     * Windows에서 PATH의 `claude`는 실행할 수 없는 sh 스크립트다 — npm이 함께 깐 `claude.cmd`가
     * 진짜 실행 파일이다. `ProcessBuilder`는 `git.exe`처럼 확장자를 알아서 붙여 주지 않으므로
     * 여기서 직접 고른다.
     */
    private fun resolveExecutable(): String? {
        executablePath?.trim()?.takeIf { it.isNotEmpty() }?.let { configured ->
            return configured.takeIf { File(it).canExecute() || canRun(it) }
        }

        return candidates().firstOrNull(::canRun)
    }

    private fun candidates(): List<String> =
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            listOf("claude.cmd", "claude.exe", "claude")
        } else {
            listOf("claude")
        }

    /** 있기만 한지 아주 싸게 확인한다. `--version`은 로그인도 네트워크도 필요 없다. */
    private fun canRun(executable: String): Boolean =
        run(listOf(executable, "--version"), timeoutSeconds = VERSION_TIMEOUT_SECONDS)?.exitCode == 0

    /**
     * [GitRepository.run]과 세 가지를 다르게 한다.
     *
     * 1. **stderr를 버리지 않는다.** 저쪽은 읽고 버려서 실패 이유가 사라진다. CLI는 "로그인이 필요하다",
     *    "사용량 한도" 같은 말을 stderr로 하는데 그걸 삼키면 사용자가 고칠 방법을 알 수 없다.
     * 2. **스트림을 다른 스레드로 빨아낸 뒤 기다린다.** 저쪽은 stdout을 끝까지 읽은 *다음* `waitFor`를
     *    불러서, 프로세스가 계속 쓰면 시한이 걸리지 않는다. git처럼 짧은 명령에서는 드러나지 않지만
     *    AI 호출은 길어서 실제로 문제가 된다.
     * 3. **`CLAUDECODE`·`CLAUDE_CODE_*`를 자식 환경에서 지운다.** IDE가 Claude Code 세션에서 실행되면
     *    그 변수들을 물려받아 중첩 세션처럼 굴 수 있다.
     */
    private fun run(
        command: List<String>,
        timeoutSeconds: Long = timeout,
        input: String? = null,
    ): ProcessResult? = try {
        val builder = ProcessBuilder(command).directory(workingDirectory)
        builder.environment().keys.removeAll { it == "CLAUDECODE" || it.startsWith("CLAUDE_CODE_") }

        val process = builder.start()

        // **읽기를 먼저 시작한다.** stdin을 다 쓰기 전에 자식이 출력으로 파이프를 채우면 서로 막힌다.
        val stdout = process.inputStream.drainOnAnotherThread()
        val stderr = process.errorStream.drainOnAnotherThread()

        process.outputStream.use { pipe ->
            input?.let { pipe.write(it.toByteArray(Charsets.UTF_8)) }
        }

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else {
            ProcessResult(process.exitValue(), stdout.join(), stderr.join())
        }
    } catch (error: Exception) {
        null
    }

    private fun java.io.InputStream.drainOnAnotherThread(): Drain =
        Drain(this).also { it.thread.start() }

    private class Drain(private val stream: java.io.InputStream) {
        @Volatile
        private var text: String = ""

        val thread: Thread = Thread {
            text = runCatching { stream.bufferedReader(Charsets.UTF_8).readText() }.getOrDefault("")
        }.apply { isDaemon = true }

        fun join(): String {
            thread.join(TimeUnit.SECONDS.toMillis(DRAIN_JOIN_SECONDS))
            return text
        }
    }

    private data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String) {

        /** CLI가 한 말을 그대로 올린다. 우리가 지어낸 문구보다 저쪽 설명이 늘 낫다. */
        fun complaint(): String {
            val said = listOf(stderr, stdout).map { it.trim() }.firstOrNull { it.isNotEmpty() }
                ?: return "Claude Code가 이유를 밝히지 않고 실패했습니다(종료 코드 $exitCode)."

            return said.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
                ?.take(MAX_COMPLAINT)
                ?: "Claude Code가 이유를 밝히지 않고 실패했습니다(종료 코드 $exitCode)."
        }
    }

    companion object {
        const val NOT_FOUND: String =
            "Claude Code CLI를 찾을 수 없습니다. 설치했다면 설정에서 실행 파일 경로를 지정하세요."
        const val TIMED_OUT: String = "Claude Code가 제때 답하지 않았습니다."
        const val UNREADABLE: String = "Claude Code의 답을 초안으로 읽지 못했습니다."

        const val DEFAULT_TIMEOUT_SECONDS: Long = 120L
        private const val VERSION_TIMEOUT_SECONDS = 10L
        private const val DRAIN_JOIN_SECONDS = 5L
        private const val MAX_COMPLAINT = 300
    }
}
