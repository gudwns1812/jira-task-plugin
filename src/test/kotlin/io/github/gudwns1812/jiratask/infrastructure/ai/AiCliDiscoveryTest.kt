package io.github.gudwns1812.jiratask.infrastructure.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 설치된 CLI를 찾아낸다.
 *
 * **프로세스를 띄우지 않고** 확인한다. 후보 목록 자체를 열어 둔 덕에, Windows 개발기에서도
 * macOS에서 어디를 뒤질지 그대로 볼 수 있다 — 그것이 이 기능에서 가장 확인하기 어려운 부분이다.
 */
class AiCliDiscoveryTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private fun discovery(
        os: OperatingSystem,
        home: File,
        environment: Map<String, String> = emptyMap(),
        canRun: (String) -> Boolean = { true },
        resolveFromShell: (OperatingSystem, String) -> String? = { _, _ -> null },
    ) = AiCliDiscovery(
        os = os,
        home = home,
        environment = { environment[it] },
        canRun = canRun,
        resolveFromShell = resolveFromShell,
    )

    private fun install(vararg segments: String): File {
        val file = segments.fold(temp.root) { directory, segment -> File(directory, segment) }
        file.parentFile.mkdirs()
        file.writeText("#!/bin/sh\n")
        return file
    }

    @Test
    fun `finds a cli that only the login shell PATH knows about`() {
        // macOS의 핵심 문제다. Dock으로 띄운 IDE의 PATH에는 이 자리가 없고, 로그인 셸에는 있다.
        val brew = install("opt", "homebrew", "bin", "claude")

        val found = discovery(
            os = OperatingSystem.MAC,
            home = temp.root,
            environment = mapOf("PATH" to brew.parentFile.absolutePath),
        ).find(listOf("claude"))

        assertEquals(brew.absolutePath, found)
    }

    @Test
    fun `finds a cli in a known install directory even with no PATH at all`() {
        // 로그인 셸 환경마저 읽지 못했을 때의 뒷받침이다. PATH가 비어 있어도 찾아내야 한다.
        val installed = install(".local", "bin", "codex")

        val found = discovery(os = OperatingSystem.MAC, home = temp.root).find(listOf("codex"))

        assertEquals(installed.absolutePath, found)
    }

    @Test
    fun `finds the npm global install on windows`() {
        // 이 개발기에서 실제로 확인한 자리다: %APPDATA%\npm\claude.cmd
        val npm = install("AppData", "Roaming", "npm", "claude.cmd")

        val found = discovery(
            os = OperatingSystem.WINDOWS,
            home = temp.root,
            environment = mapOf("APPDATA" to File(temp.root, "AppData/Roaming").absolutePath),
        ).find(listOf("claude.cmd", "claude.exe", "claude"))

        assertEquals(npm.absolutePath, found)
    }

    @Test
    fun `prefers the name the platform can actually run`() {
        // Windows에서 PATH의 `claude`는 실행할 수 없는 sh 스크립트다. `.cmd`가 진짜 실행 파일이다.
        install("bin", "claude")
        val cmd = install("bin", "claude.cmd")

        val found = discovery(
            os = OperatingSystem.WINDOWS,
            home = temp.root,
            environment = mapOf("PATH" to File(temp.root, "bin").absolutePath),
        ).find(listOf("claude.cmd", "claude.exe", "claude"))

        assertEquals(cmd.absolutePath, found)
    }

    @Test
    fun `looks through the node versions nvm installed, newest first`() {
        install(".nvm", "versions", "node", "v18.20.4", "bin", "claude")
        val newer = install(".nvm", "versions", "node", "v22.11.0", "bin", "claude")

        val found = discovery(os = OperatingSystem.MAC, home = temp.root).find(listOf("claude"))

        assertEquals("여러 버전에 깔려 있으면 최신 쪽을 고른다", newer.absolutePath, found)
    }

    @Test
    fun `falls back to the bare name so the jvm PATH still gets a chance`() {
        // 우리가 모르는 자리에 깔렸어도 JVM이 아는 PATH에 있으면 ProcessBuilder가 찾아낸다.
        val candidates = discovery(os = OperatingSystem.LINUX, home = temp.root).candidates(listOf("codex"))

        assertEquals(listOf("codex"), candidates)
    }

    @Test
    fun `finds the real Codex executable behind a macOS shell alias`() {
        val actual = install("custom", "bin", "codex")

        val found = discovery(
            os = OperatingSystem.MAC,
            home = temp.root,
            canRun = { it == actual.absolutePath },
            resolveFromShell = { os, command ->
                assertEquals(OperatingSystem.MAC, os)
                assertEquals("codex", command)
                actual.absolutePath
            },
        ).find(listOf("codex"))

        assertEquals(actual.absolutePath, found)
    }

    @Test
    fun `never asks a shell to resolve a command on Windows`() {
        var called = false

        val found = discovery(
            os = OperatingSystem.WINDOWS,
            home = temp.root,
            canRun = { false },
            resolveFromShell = { _, _ ->
                called = true
                "C:/fake/codex.cmd"
            },
        ).find(listOf("codex.cmd", "codex"))

        assertNull(found)
        assertTrue(!called)
    }

    @Test
    fun `keeps reporting a missing CLI when the shell cannot resolve its alias`() {
        val found = discovery(
            os = OperatingSystem.MAC,
            home = temp.root,
            canRun = { false },
            resolveFromShell = { _, _ -> null },
        ).find(listOf("codex"))

        assertNull(found)
    }

    @Test
    fun `answers with nothing when the cli is not installed`() {
        val found = discovery(
            os = OperatingSystem.MAC,
            home = temp.root,
            canRun = { false },
        ).find(listOf("claude"))

        assertNull(found)
    }

    @Test
    fun `searches the PATH before the places it merely guesses`() {
        install(".local", "bin", "claude")
        val onPath = install("elsewhere", "claude")

        val candidates = discovery(
            os = OperatingSystem.MAC,
            home = temp.root,
            environment = mapOf("PATH" to onPath.parentFile.absolutePath),
        ).candidates(listOf("claude"))

        assertEquals("사용자가 실제로 쓰는 자리가 먼저다", onPath.absolutePath, candidates.first())
        assertTrue(candidates.any { it.endsWith(".local${File.separator}bin${File.separator}claude") })
    }

    @Test
    fun `does not look for windows-only names on a mac`() {
        assertEquals(listOf("claude"), ClaudeCodeCli.binaryNames(OperatingSystem.MAC))
        assertEquals(listOf("codex"), CodexCli.binaryNames(OperatingSystem.LINUX))
        assertTrue(ClaudeCodeCli.binaryNames(OperatingSystem.WINDOWS).contains("claude.cmd"))
        assertTrue(CodexCli.binaryNames(OperatingSystem.WINDOWS).contains("codex.cmd"))
    }
}
