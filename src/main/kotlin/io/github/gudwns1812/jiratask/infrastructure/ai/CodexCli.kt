package io.github.gudwns1812.jiratask.infrastructure.ai

import java.io.File

object CodexCli : AiCli {

    private const val HOME_DIRECTORY = ".codex"

    private val MODEL_LINE = Regex("""^\s*model\s*=\s*["'](.+?)["']""", RegexOption.MULTILINE)

    override val id: String = "codex"

    override val displayName: String = "Codex"

    override val models: List<String> = listOf(
        "gpt-5.6-sol",
        "gpt-5.6-terra",
        "gpt-5.6-luna",
        "gpt-5.6",
    )

    override fun configuredModel(userHome: File): String? =
        MODEL_LINE.find(readOrEmpty(File(userHome, "$HOME_DIRECTORY/config.toml")))?.groupValues?.get(1)

    private fun readOrEmpty(file: File): String =
        runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")

    override val installHint: String = "`npm install -g @openai/codex`로 설치하고 `codex login`을 마치세요."

    override fun binaryNames(os: OperatingSystem): List<String> = when (os) {
        OperatingSystem.WINDOWS -> listOf("codex.cmd", "codex.exe", "codex")
        else -> listOf("codex")
    }

    override fun isSessionVariable(name: String): Boolean = name.startsWith("CODEX_SANDBOX")

    override fun invocationOf(executable: String, model: String?): Invocation {
        val lastMessage = File.createTempFile("jira-task-codex", ".txt")

        val command = buildList {
            add(executable)
            add("exec")
            add(SKIP_GIT_REPO_CHECK)
            addAll(SANDBOX)
            model?.trim()?.takeIf { it.isNotEmpty() }?.let {
                add("--model")
                add(it)
            }
            add(OUTPUT_LAST_MESSAGE)
            add(lastMessage.absolutePath)
            add("-")
        }

        return Invocation(
            command = command,
            readText = { result -> textOf(lastMessage, result.stdout) },
            cleanUp = { lastMessage.delete() },
        )
    }

    fun textOf(lastMessage: File, stdout: String): String? {
        val written = runCatching { lastMessage.readText(Charsets.UTF_8) }.getOrDefault("")
        return written.trim().ifEmpty { null } ?: AiDraftResponse.objectBlockIn(stdout)
    }

    const val SKIP_GIT_REPO_CHECK: String = "--skip-git-repo-check"
    const val OUTPUT_LAST_MESSAGE: String = "--output-last-message"

    val SANDBOX: List<String> = listOf("--sandbox", "read-only")
}
