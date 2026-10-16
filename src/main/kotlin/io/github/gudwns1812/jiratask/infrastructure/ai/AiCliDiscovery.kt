package io.github.gudwns1812.jiratask.infrastructure.ai

import java.io.File

class AiCliDiscovery(
    private val os: OperatingSystem = OperatingSystem.current(),
    private val home: File = File(System.getProperty("user.home").orEmpty()),
    private val environment: (String) -> String? = ShellEnvironment::valueOf,
    private val canRun: (String) -> Boolean,
    private val resolveFromShell: (OperatingSystem, String) -> String? = ShellEnvironment::executablePath,
) {
    fun find(binaryNames: List<String>): String? =
        candidates(binaryNames).firstOrNull(canRun)
            ?: shellCandidates(binaryNames).firstOrNull(canRun)

    fun candidates(binaryNames: List<String>): List<String> {
        val directories = pathDirectories() + knownDirectories()

        val found = directories.flatMap { directory ->
            binaryNames.mapNotNull { name ->
                File(directory, name).takeIf { it.isFile }?.absolutePath
            }
        }

        return (found + binaryNames).distinct()
    }

    private fun shellCandidates(binaryNames: List<String>): List<String> {
        if (os == OperatingSystem.WINDOWS) {
            return emptyList()
        }

        return binaryNames.mapNotNull { name ->
            resolveFromShell(os, name)?.let(::File)?.takeIf(File::isFile)?.absolutePath
        }.distinct()
    }

    private fun pathDirectories(): List<String> =
        environment("PATH").orEmpty()
            .split(File.pathSeparatorChar)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun knownDirectories(): List<String> = when (os) {
        OperatingSystem.WINDOWS -> listOfNotNull(
            environment("APPDATA")?.let { "$it${File.separator}npm" },
            homePath(".local", "bin"),
            homePath("AppData", "Roaming", "npm"),
            homePath("scoop", "shims"),
            homePath(".bun", "bin"),
        )

        OperatingSystem.MAC, OperatingSystem.LINUX -> listOfNotNull(
            homePath(".local", "bin"),
            "/opt/homebrew/bin",
            "/usr/local/bin",
            "/home/linuxbrew/.linuxbrew/bin".takeIf { os == OperatingSystem.LINUX },
            homePath(".npm-global", "bin"),
            homePath(".bun", "bin"),
            homePath(".claude", "local"),
        ) + nodeVersionDirectories()
    }

    private fun nodeVersionDirectories(): List<String> =
        File(home, ".nvm/versions/node").listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .sortedByDescending { it.name }
            .map { File(it, "bin").absolutePath }

    private fun homePath(vararg segments: String): String =
        segments.fold(home) { directory, segment -> File(directory, segment) }.absolutePath
}
