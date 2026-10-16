package io.github.gudwns1812.jiratask.infrastructure.ai

import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

class AiCliRunner(
    private val cli: AiCli,
    private val workingDirectory: File,
    private val executablePath: String? = null,
    private val model: String? = null,
    private val timeout: Long = DEFAULT_TIMEOUT_SECONDS,
    private val os: OperatingSystem = OperatingSystem.current(),
    private val run: (List<String>, String?, Long) -> ProcessResult? = { command, input, seconds ->
        execute(command, input, seconds, workingDirectory, cli::isSessionVariable, os)
    },
) {
    fun ask(prompt: String): AiAnswer {
        val executable = when (val location = locate()) {
            is Location.Found -> location.executable
            is Location.Missing -> return AiAnswer.NotAvailable(location.message)
        }

        val invocation = cli.invocationOf(executable, model)
        return try {
            answerOf(invocation, prompt)
        } finally {
            runCatching(invocation.cleanUp)
        }
    }

    private fun answerOf(invocation: Invocation, prompt: String): AiAnswer {
        val result = run(invocation.command, prompt, timeout)
            ?: return AiAnswer.Failed("${cli.displayName}이(가) 제때 답하지 않았습니다.")
        invocation.errorIn(result)?.let { return AiAnswer.Failed(it) }
        if (result.exitCode != 0) {
            return AiAnswer.Failed(result.complaint(cli.displayName))
        }

        return invocation.readText(result)?.takeIf { it.isNotBlank() }?.let(AiAnswer::Said)
            ?: AiAnswer.Failed(unreadable(cli))
    }

    private sealed interface Location {
        data class Found(val executable: String) : Location
        data class Missing(val message: String) : Location
    }

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

    private fun canRun(executable: String): Boolean =
        run(listOf(executable, "--version"), null, VERSION_TIMEOUT_SECONDS)?.exitCode == 0

    companion object {

        const val DEFAULT_TIMEOUT_SECONDS: Long = 120L
        private const val VERSION_TIMEOUT_SECONDS = 10L
        private const val DRAIN_JOIN_SECONDS = 5L

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
