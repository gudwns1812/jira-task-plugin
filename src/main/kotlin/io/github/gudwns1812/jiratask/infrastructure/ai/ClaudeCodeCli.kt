package io.github.gudwns1812.jiratask.infrastructure.ai

import java.io.File

import com.google.gson.JsonObject
import com.google.gson.JsonParser

object ClaudeCodeCli : AiCli {

    private const val HOME_DIRECTORY = ".claude"

    private val MODEL_KEY = Regex(""""model"\s*:\s*"(.+?)"""")

    override val id: String = "claude"

    override val displayName: String = "Claude Code"

    override val models: List<String> = listOf(
        "fable",
        "opus",
        "sonnet",
        "claude-opus-5",
        "claude-sonnet-5",
        "claude-fable-5",
        "claude-haiku-4-5",
    )

    override fun configuredModel(userHome: File): String? =
        MODEL_KEY.find(readOrEmpty(File(userHome, "$HOME_DIRECTORY/settings.json")))?.groupValues?.get(1)

    private fun readOrEmpty(file: File): String =
        runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")

    override val installHint: String = "`npm install -g @anthropic-ai/claude-code`로 설치하고 `claude` 로그인을 마치세요."

    override fun binaryNames(os: OperatingSystem): List<String> = when (os) {
        OperatingSystem.WINDOWS -> listOf("claude.cmd", "claude.exe", "claude")
        else -> listOf("claude")
    }

    override fun isSessionVariable(name: String): Boolean =
        name == "CLAUDECODE" || name.startsWith("CLAUDE_CODE_")

    override fun invocationOf(executable: String, model: String?): Invocation {
        val command = buildList {
            add(executable)
            add("-p")
            add("--output-format")
            add("json")
            add(RESTRICTED)
            model?.trim()?.takeIf { it.isNotEmpty() }?.let {
                add("--model")
                add(it)
            }
        }

        return Invocation(
            command = command,
            readText = { textOf(it.stdout) },
            errorIn = { outdated(it) ?: errorOf(it.stdout) },
        )
    }

    fun outdated(result: ProcessResult): String? {
        val said = result.stderr + result.stdout
        if (!said.contains(RESTRICTED) || !said.contains("unknown option")) {
            return null
        }

        return OUTDATED
    }

    fun textOf(cliOutput: String): String? {
        val envelope = envelopeIn(cliOutput)

        if (envelope != null) {
            return envelope.text("result") ?: envelope.text("error")
        }

        return cliOutput.trim().ifEmpty { null }
    }

    fun errorOf(cliOutput: String): String? {
        val envelope = envelopeIn(cliOutput) ?: return null
        val failed = envelope.get("is_error")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
            ?.takeIf { it.isBoolean }?.asBoolean == true ||
            envelope.text("subtype")?.startsWith("error") == true
        if (!failed) {
            return null
        }

        return envelope.text("result") ?: envelope.text("error") ?: FAILED_WITHOUT_REASON
    }

    private fun envelopeIn(cliOutput: String): JsonObject? = cliOutput.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("{") }
        .mapNotNull { runCatching { JsonParser.parseString(it) }.getOrNull() as? JsonObject }
        .firstOrNull(::looksLikeEnvelope)

    private fun looksLikeEnvelope(json: JsonObject): Boolean =
        json.has("result") || json.has("error") || json.has("session_id")

    private fun JsonObject.text(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?.takeIf { it.isNotBlank() }

    const val FAILED_WITHOUT_REASON: String = "Claude Code가 이유를 밝히지 않고 실패했습니다."

    private const val RESTRICTED: String = "--restricted"

    const val OUTDATED: String =
        "Claude Code 버전이 낮습니다. 터미널에서 `claude update`를 실행해 올린 뒤 다시 시도하세요."
}
