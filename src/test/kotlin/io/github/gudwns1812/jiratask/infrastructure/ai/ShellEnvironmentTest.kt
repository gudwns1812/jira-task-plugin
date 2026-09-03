package io.github.gudwns1812.jiratask.infrastructure.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShellEnvironmentTest {

    @Test
    fun `does not start a shell for a command name that is not safe to resolve`() {
        assertNull(ShellEnvironment.executablePath(OperatingSystem.MAC, "codex; echo unsafe"))
    }

    @Test
    fun `does not use a POSIX shell to resolve commands on Windows`() {
        assertNull(ShellEnvironment.executablePath(OperatingSystem.WINDOWS, "codex"))
    }

    @Test
    fun `prepends the macOS login shell PATH for a child process`() {
        assertEquals(
            "/opt/homebrew/bin:/usr/bin:/bin",
            ShellEnvironment.pathForProcess(
                os = OperatingSystem.MAC,
                inheritedPath = "/usr/bin:/bin",
                loginShellPath = "/opt/homebrew/bin:/usr/bin",
            ),
        )
    }

    @Test
    fun `keeps the inherited PATH on Windows`() {
        assertEquals(
            "C:\\Windows\\System32;C:\\Tools",
            ShellEnvironment.pathForProcess(
                os = OperatingSystem.WINDOWS,
                inheritedPath = "C:\\Windows\\System32;C:\\Tools",
                loginShellPath = "C:\\Users\\me\\bin;C:\\Tools",
            ),
        )
    }

    @Test
    fun `keeps the inherited PATH when a login shell did not provide one`() {
        assertEquals(
            "/usr/bin:/bin",
            ShellEnvironment.pathForProcess(OperatingSystem.LINUX, "/usr/bin:/bin", null),
        )
    }
}
