package io.github.gudwns1812.jiratask.infrastructure.ai

import com.intellij.util.EnvironmentUtil
import java.io.File
import java.util.concurrent.TimeUnit

object ShellEnvironment {

    private val COMMAND_NAME = Regex("^[A-Za-z0-9._-]+$")

    private const val RESOLVE_TIMEOUT_SECONDS = 5L

    fun valueOf(name: String): String? =
        runCatching { EnvironmentUtil.getEnvironmentMap()[name] }.getOrNull()
            ?: System.getenv(name)

    fun pathForProcess(
        os: OperatingSystem,
        inheritedPath: String?,
        loginShellPath: String?,
    ): String? {
        if (os == OperatingSystem.WINDOWS || loginShellPath.isNullOrBlank()) {
            return inheritedPath
        }

        val separator = ":"
        return (loginShellPath.split(separator) + inheritedPath.orEmpty().split(separator))
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .joinToString(separator)
            .ifEmpty { null }
    }

    fun executablePath(os: OperatingSystem, command: String): String? {
        if (os == OperatingSystem.WINDOWS || !COMMAND_NAME.matches(command)) {
            return null
        }

        val shell = shellFor(os) ?: return null
        val output = runCatching {
            val process = ProcessBuilder(
                shell.absolutePath,
                "-ilc",
                "unalias $command 2>/dev/null; command -v $command",
            ).redirectErrorStream(true).start()
            if (!process.waitFor(RESOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@runCatching ""
            }
            process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrDefault("")

        return output.lineSequence()
            .map(String::trim)
            .lastOrNull { File(it).isFile }
            ?.let(::File)
            ?.absolutePath
    }

    private fun shellFor(os: OperatingSystem): File? {
        valueOf("SHELL")?.let(::File)?.takeIf(File::isFile)?.let { return it }

        val defaults = when (os) {
            OperatingSystem.MAC -> listOf("/bin/zsh", "/bin/bash")
            OperatingSystem.LINUX -> listOf("/bin/bash", "/bin/sh")
            OperatingSystem.WINDOWS -> emptyList()
        }
        return defaults.map(::File).firstOrNull(File::isFile)
    }
}
