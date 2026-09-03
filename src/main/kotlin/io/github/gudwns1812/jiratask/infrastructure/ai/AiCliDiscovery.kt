package io.github.gudwns1812.jiratask.infrastructure.ai

import java.io.File

/**
 * 설치된 AI CLI를 찾아낸다. **사용자에게 경로를 외워 적게 하지 않는 것이 이 클래스의 목적이다.**
 *
 * npm·homebrew·공식 인스톨러가 실행 파일을 놓는 자리는 몇 곳뿐이고, provider가 달라도 그 자리는
 * 같다 — 다른 것은 실행 파일 이름뿐이다. 그래서 이름만 받아 찾는다.
 *
 * ### macOS에서 PATH를 직접 읽는 이유
 *
 * Dock이나 Spotlight로 띄운 IDE는 로그인 셸의 PATH를 물려받지 않는다. `ProcessBuilder`가 보는
 * PATH에는 `/opt/homebrew/bin`도 `~/.local/bin`도 없어서, **터미널에서 멀쩡히 도는 CLI를 두고도**
 * "찾을 수 없습니다"만 보게 된다. IntelliJ의 `EnvironmentUtil`은 로그인 셸을 한 번 띄워 그 환경을
 * 읽어 두므로 그 PATH를 받아 우리가 직접 훑는다.
 */
class AiCliDiscovery(
    private val os: OperatingSystem = OperatingSystem.current(),
    private val home: File = File(System.getProperty("user.home").orEmpty()),
    /** 로그인 셸까지 반영된 환경 변수. 기본은 IDE가 읽어 둔 것이다. */
    private val environment: (String) -> String? = ShellEnvironment::valueOf,
    /** 이 경로로 실제 부를 수 있는지. `--version`을 한 번 불러 보는 일이라 프로세스를 띄운다. */
    private val canRun: (String) -> Boolean,
    private val resolveFromShell: (OperatingSystem, String) -> String? = ShellEnvironment::executablePath,
) {

    /**
     * [binaryNames] 중 실제로 부를 수 있는 첫 번째의 경로. 하나도 없으면 null.
     *
     * 이름 여러 개를 받는 이유는 Windows다 — PATH의 `claude`는 실행할 수 없는 sh 스크립트이고
     * npm이 함께 깐 `claude.cmd`가 진짜 실행 파일이다. `ProcessBuilder`는 `git.exe`처럼 확장자를
     * 붙여 주지 않으므로 후보를 우리가 늘어놓는다.
     */
    fun find(binaryNames: List<String>): String? =
        candidates(binaryNames).firstOrNull(canRun)
            ?: shellCandidates(binaryNames).firstOrNull(canRun)

    /**
     * 찾아볼 자리를 순서대로 늘어놓는다. 프로세스를 띄우지 않으므로 **이 목록 자체를 시험할 수 있다** —
     * OS를 값으로 받는 것과 함께, Windows 개발기에서 mac 분기를 확인하는 유일한 방법이다.
     *
     * 맨 뒤에 이름만 남기는 것은 뒷받침이다. 우리가 미처 모르는 자리에 깔렸더라도 JVM의 PATH에만
     * 있으면 `ProcessBuilder`가 찾아낸다.
     */
    fun candidates(binaryNames: List<String>): List<String> {
        val directories = pathDirectories() + knownDirectories()

        val found = directories.flatMap { directory ->
            binaryNames.mapNotNull { name ->
                File(directory, name).takeIf { it.isFile }?.absolutePath
            }
        }

        return (found + binaryNames).distinct()
    }

    /**
     * A shell alias is not an executable path. On macOS and Linux, resolve the command behind it
     * only after checking PATH and standard install locations first.
     */
    private fun shellCandidates(binaryNames: List<String>): List<String> {
        if (os == OperatingSystem.WINDOWS) {
            return emptyList()
        }

        return binaryNames.mapNotNull { name ->
            resolveFromShell(os, name)?.let(::File)?.takeIf(File::isFile)?.absolutePath
        }.distinct()
    }

    /** 로그인 셸까지 반영된 PATH. macOS에서 이것이 없으면 아래 [knownDirectories]만으로 버텨야 한다. */
    private fun pathDirectories(): List<String> =
        environment("PATH").orEmpty()
            .split(File.pathSeparatorChar)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * npm·homebrew·공식 인스톨러가 쓰는 자리들.
     *
     * PATH에 이미 있으면 위에서 걸리므로 여기는 **PATH가 비어 있거나 잘려 있을 때의 뒷받침**이다.
     * 자리를 늘리는 것은 싸다 — 파일이 없으면 후보로 오르지도 않는다.
     */
    private fun knownDirectories(): List<String> = when (os) {
        OperatingSystem.WINDOWS -> listOfNotNull(
            // npm 전역 설치. 실제로 확인한 자리다.
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
            // Claude Code가 자기 자신을 두는 자리.
            homePath(".claude", "local"),
        ) + nodeVersionDirectories()
    }

    /**
     * nvm으로 깐 node의 bin들. 버전마다 자리가 달라 훑어야 한다.
     *
     * 최신 버전이 먼저 오도록 이름 역순으로 둔다 — 여러 버전에 깔려 있으면 사용자가 평소에 쓰는
     * 쪽일 가능성이 높다.
     */
    private fun nodeVersionDirectories(): List<String> =
        File(home, ".nvm/versions/node").listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .sortedByDescending { it.name }
            .map { File(it, "bin").absolutePath }

    private fun homePath(vararg segments: String): String =
        segments.fold(home) { directory, segment -> File(directory, segment) }.absolutePath
}
