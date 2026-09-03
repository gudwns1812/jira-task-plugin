package io.github.gudwns1812.jiratask.infrastructure.ai

import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 사용자가 설치하고 로그인해 둔 **AI CLI**를 비대화형으로 부른다.
 *
 * API 키를 쓰지 않는 이유는 요금 때문이다. 제공사의 Messages/Responses API는 언제나 API 키로
 * 사용량 과금되고 구독은 그것을 덮지 않는다. 구독이 덮는 것은 각 사의 CLI라, 구독으로 쓰려면
 * 사용자의 CLI 로그인을 그대로 타는 이 길뿐이다. **플러그인은 어떤 자격 증명도 갖지 않는다.**
 *
 * 어느 CLI를 부르는지는 [cli]가 알고, 여기서는 provider와 무관한 일만 한다 —
 * 실행 파일 찾기, 스트림 교착 피하기, 시한, 중첩 세션 끊기, 실패 문구 고르기.
 */
class AiCliRunner(
    private val cli: AiCli,
    private val workingDirectory: File,
    /** 설정에서 받은 실행 파일 경로. 비어 있으면 [AiCliLocations]가 찾는다. */
    private val executablePath: String? = null,
    /** 설정에서 받은 모델. **비어 있으면 붙이지 않는다** — 사용자가 CLI에 정해 둔 것을 따른다. */
    private val model: String? = null,
    private val timeout: Long = DEFAULT_TIMEOUT_SECONDS,
    private val os: OperatingSystem = OperatingSystem.current(),
    /**
     * 프로세스를 띄우는 일. **테스트가 가짜를 끼우는 이음매다.**
     *
     * 이 자리가 없으면 CLI를 실제로 깔지 않는 한 [ask]의 어느 갈래도 확인할 수 없다.
     */
    private val run: (List<String>, String?, Long) -> ProcessResult? = { command, input, seconds ->
        execute(command, input, seconds, workingDirectory, cli::isSessionVariable, os)
    },
) {

    /** [prompt]를 stdin으로 넘기고 모델이 낸 글을 받아 온다. 몇십 초가 걸린다 — 백그라운드에서만 부른다. */
    fun ask(prompt: String): AiAnswer {
        val executable = when (val location = locate()) {
            is Location.Found -> location.executable
            is Location.Missing -> return AiAnswer.NotAvailable(location.message)
        }

        // 프롬프트는 인자가 아니라 stdin으로 넘긴다 — 이유는 [MergeRequestDraftPrompt.stdinOf]에 적어 두었다.
        val invocation = cli.invocationOf(executable, model)
        return try {
            answerOf(invocation, prompt)
        } finally {
            // 임시 파일을 쓰는 CLI가 있다. 실패했을 때도 반드시 치운다.
            runCatching(invocation.cleanUp)
        }
    }

    private fun answerOf(invocation: Invocation, prompt: String): AiAnswer {
        val result = run(invocation.command, prompt, timeout)
            ?: return AiAnswer.Failed("${cli.displayName}이(가) 제때 답하지 않았습니다.")
        if (result.exitCode != 0) {
            // 옵션 하나 때문에 거절당했을 수 있다. CLI가 물러설 자리를 알려 주면 한 번만 다시 부른다.
            invocation.fallbackFor(result)?.let { retry ->
                return try {
                    answerOf(retry, prompt)
                } finally {
                    runCatching(retry.cleanUp)
                }
            }
            return AiAnswer.Failed(result.complaint(cli.displayName))
        }

        // 종료 코드가 0이어도 실패일 수 있다. CLI가 한 말을 그대로 올린다 —
        // 우리가 지어낸 문구보다 "로그인이 만료됐습니다"가 언제나 낫다.
        invocation.errorIn(result)?.let { return AiAnswer.Failed(it) }

        return invocation.readText(result)?.takeIf { it.isNotBlank() }?.let(AiAnswer::Said)
            ?: AiAnswer.Failed(unreadable(cli))
    }

    private sealed interface Location {
        data class Found(val executable: String) : Location
        data class Missing(val message: String) : Location
    }

    /**
     * 설정에 적어 둔 경로가 있으면 그것만 본다.
     *
     * 실패했을 때 "찾을 수 없습니다"로 뭉뚱그리지 않는다 — 사용자가 직접 적어 넣은 값이 문제인데
     * 그 사실을 말해 주지 않으면 엉뚱한 자리를 뒤지게 된다.
     */
    private fun locate(): Location {
        executablePath?.trim()?.takeIf { it.isNotEmpty() }?.let { configured ->
            return if (canRun(configured)) {
                Location.Found(configured)
            } else {
                Location.Missing(
                    "설정에 적어 둔 경로로 ${cli.displayName}을(를) 부를 수 없습니다: $configured",
                )
            }
        }

        return AiCliLocations.locate(cli, os, ::canRun)
            ?.let(Location::Found)
            ?: Location.Missing(cli.notFound())
    }

    /** 있기만 한지 아주 싸게 확인한다. `--version`은 로그인도 네트워크도 필요 없다. */
    private fun canRun(executable: String): Boolean =
        run(listOf(executable, "--version"), null, VERSION_TIMEOUT_SECONDS)?.exitCode == 0

    companion object {

        const val DEFAULT_TIMEOUT_SECONDS: Long = 120L
        private const val VERSION_TIMEOUT_SECONDS = 10L
        private const val DRAIN_JOIN_SECONDS = 5L

        /**
         * 이 CLI가 어디에 깔려 있는지. 없으면 null.
         *
         * 설정 화면이 **부르기 전에** 물어보는 자리다. 사용자가 경로를 외워 적는 대신 화면이 찾아
         * 보여 주는 것이 이 기능의 요점이라, 초안을 만들 때와 같은 탐지를 그대로 쓴다.
         * 프로세스를 띄우므로 EDT에서 부르지 않는다.
         */
        fun locate(cli: AiCli, os: OperatingSystem = OperatingSystem.current()): String? =
            AiCliLocations.locate(cli, os) { executable ->
                execute(
                    command = listOf(executable, "--version"),
                    input = null,
                    timeoutSeconds = VERSION_TIMEOUT_SECONDS,
                    workingDirectory = File(System.getProperty("user.dir")),
                    isSessionVariable = cli::isSessionVariable,
                    os = os,
                )?.exitCode == 0
            }

        fun unreadable(cli: AiCli): String = "${cli.displayName}의 답을 초안으로 읽지 못했습니다."

        /**
         * [io.github.gudwns1812.jiratask.infrastructure.GitRepository]의 실행과 세 가지를 다르게 한다.
         *
         * 1. **stderr를 버리지 않는다.** 저쪽은 읽고 버려서 실패 이유가 사라진다. CLI는 "로그인이 필요하다",
         *    "사용량 한도" 같은 말을 stderr로 하는데 그걸 삼키면 사용자가 고칠 방법을 알 수 없다.
         * 2. **스트림을 다른 스레드로 빨아낸 뒤 기다린다.** 저쪽은 stdout을 끝까지 읽은 *다음* `waitFor`를
         *    불러서, 프로세스가 계속 쓰면 시한이 걸리지 않는다. git처럼 짧은 명령에서는 드러나지 않지만
         *    AI 호출은 길어서 실제로 문제가 된다.
         * 3. **중첩 세션 변수를 자식 환경에서 지운다.** IDE가 AI CLI 세션 안에서 실행되면 그 변수들을
         *    물려받아 자기 안에서 자기를 다시 부른 것처럼 굴 수 있다. 어느 변수인지는 CLI가 안다.
         */
        private fun execute(
            command: List<String>,
            input: String?,
        timeoutSeconds: Long,
        workingDirectory: File,
        isSessionVariable: (String) -> Boolean,
        os: OperatingSystem,
    ): ProcessResult? = try {
        val builder = ProcessBuilder(command).directory(workingDirectory)
        builder.environment().keys.removeAll(isSessionVariable)
        ShellEnvironment.pathForProcess(
            os = os,
            inheritedPath = builder.environment()["PATH"],
            loginShellPath = ShellEnvironment.valueOf("PATH"),
        )?.let { builder.environment()["PATH"] = it }

        val process = builder.start()

            // **읽기를 먼저 시작한다.** stdin을 다 쓰기 전에 자식이 출력으로 파이프를 채우면 서로 막힌다.
            val stdout = Drain(process.inputStream)
            val stderr = Drain(process.errorStream)

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

        private class Drain(stream: InputStream) {
            @Volatile
            private var text: String = ""

            private val thread: Thread = Thread {
                text = runCatching { stream.bufferedReader(Charsets.UTF_8).readText() }.getOrDefault("")
            }.apply { isDaemon = true; start() }

            fun join(): String {
                thread.join(TimeUnit.SECONDS.toMillis(DRAIN_JOIN_SECONDS))
                return text
            }
        }
    }
}
