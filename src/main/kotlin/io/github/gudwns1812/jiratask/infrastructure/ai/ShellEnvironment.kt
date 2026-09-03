package io.github.gudwns1812.jiratask.infrastructure.ai

import com.intellij.util.EnvironmentUtil
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 로그인 셸까지 반영된 환경 변수.
 *
 * `System.getenv()`를 바로 쓰지 않는 이유는 macOS다. Dock·Spotlight로 띄운 IDE의 환경에는
 * 사용자가 `.zshrc`에 넣어 둔 PATH가 들어 있지 않다. IntelliJ는 시작할 때 로그인 셸을 한 번 띄워
 * 그 환경을 읽어 두므로 그것을 먼저 본다.
 *
 * 플랫폼이 없는 곳(단위 테스트)에서는 [EnvironmentUtil]이 답하지 못할 수 있다. 그때는 조용히
 * `System.getenv()`로 물러난다 — 환경 변수를 읽는 일이 테스트를 깨뜨릴 이유는 없다.
 */
object ShellEnvironment {

    private val COMMAND_NAME = Regex("^[A-Za-z0-9._-]+$")

    private const val RESOLVE_TIMEOUT_SECONDS = 5L

    fun valueOf(name: String): String? =
        runCatching { EnvironmentUtil.getEnvironmentMap()[name] }.getOrNull()
            ?: System.getenv(name)

    /**
     * An IDE launched from the macOS Dock can lack Homebrew's directory even though the login
     * shell has it. Preserve the IDE path, but put the login-shell path first so a Node shebang
     * in a Homebrew-installed CLI can find `node`.
     */
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

    /**
     * Resolve the executable behind a login-shell alias such as
     * `alias codex='CODEX_HOME=~/.codex codex'`.
     *
     * `~/.codex` holds Codex configuration and credentials, not the executable. The command name
     * is deliberately restricted before it is embedded in the shell command.
     */
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
