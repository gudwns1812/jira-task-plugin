package io.github.gudwns1812.jiratask.infrastructure.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.gudwns1812.jiratask.domain.commit.CommitType
import io.github.gudwns1812.jiratask.domain.commit.WrittenCommitMessage
import io.github.gudwns1812.jiratask.domain.mr.WrittenDraft

object AiDraftResponse {
    fun parse(text: String?): WrittenDraft? {
        val obj = objectIn(text) ?: return null

        val title = obj.text("title")?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        if (title.isEmpty()) {
            return null
        }

        return WrittenDraft(title = title, description = obj.text("description").orEmpty().trim())
    }

    fun commit(text: String?): WrittenCommitMessage? {
        val obj = objectIn(text) ?: return null

        val line = obj.text("summary")?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        val repeated = CommitType.of(line.substringBefore(':', ""))
        val type = CommitType.of(obj.text("type")) ?: repeated ?: return null

        val summary = (if (repeated != null) line.substringAfter(':') else line)
            .trim()
            .trimEnd('.')
            .trim()
        if (summary.isEmpty()) {
            return null
        }

        return WrittenCommitMessage(type, summary)
    }

    private fun objectIn(text: String?): JsonObject? {
        val json = unwrapFence(text.orEmpty()).ifBlank { return null }
        return runCatching { JsonParser.parseString(json) }.getOrNull() as? JsonObject
    }

    private fun unwrapFence(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) {
            return trimmed
        }

        return trimmed.removePrefix("```")
            .substringAfter('\n', "")
            .substringBeforeLast("```")
            .trim()
    }

    fun objectBlockIn(output: String): String? {
        val start = output.indexOf('{')
        val end = output.lastIndexOf('}')
        if (start < 0 || end <= start) {
            return null
        }
        return output.substring(start, end + 1)
    }

    private fun JsonObject.text(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?.takeIf { it.isNotBlank() }
}
