package io.github.gudwns1812.jiratask.infrastructure

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.gudwns1812.jiratask.domain.mr.WrittenDraft

/**
 * Claude Code가 돌려준 것을 초안으로 읽는다. 껍질이 두 겹이다.
 *
 * 1. `--output-format json`의 봉투 — CLI가 씌운다. 모델이 낸 글은 `result` 안에 있다.
 * 2. 그 안의 JSON — 모델이 낸다. 지시해도 코드펜스로 감싸 오는 일이 있어 벗겨 낸다.
 *
 * 도메인이 아니라 여기 두는 이유는 도메인이 JDK 말고는 아무것도 import하지 않기 때문이다
 * (ARCHITECTURE.md 114-119). JSON 파서를 도메인에 들이지 않는다.
 */
object ClaudeDraftResponse {

    /**
     * CLI 봉투에서 모델이 낸 글만 꺼낸다.
     *
     * **줄 단위로 찾는다.** stdout에는 봉투 말고도 다른 줄이 섞여 나온다 — 실제로
     * `Client.listTools() called but server does not advertise tools capability` 같은
     * MCP 서버 경고가 함께 찍힌다. 통째로 파싱하면 그 한 줄 때문에 전부 실패한다.
     */
    fun textOf(cliOutput: String): String? {
        val envelope = cliOutput.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") }
            .mapNotNull { runCatching { JsonParser.parseString(it) }.getOrNull() as? JsonObject }
            .firstOrNull { it.has("result") || it.has("error") }

        if (envelope != null) {
            // CLI가 오류를 봉투에 담아 주면 그것을 그대로 위로 올린다.
            envelope.text("result")?.let { return it }
            envelope.text("error")?.let { return it }
        }

        // 봉투를 못 찾았다. 모델이 낸 JSON이 바로 왔을 수도 있으니 한 번은 더 읽어 본다.
        return cliOutput.trim().ifEmpty { null }
    }

    /** 모델이 낸 글에서 제목과 본문을 읽는다. 읽어 낼 수 없으면 null — 규칙 기반 초안이 그대로 남는다. */
    fun parse(text: String?): WrittenDraft? {
        val json = unwrapFence(text.orEmpty()).ifBlank { return null }
        val obj = runCatching { JsonParser.parseString(json) }.getOrNull() as? JsonObject ?: return null

        val title = obj.text("title")?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        if (title.isEmpty()) {
            // 제목 없는 초안은 쓸 수 없다. MR 제목은 비울 수 없기 때문이다.
            return null
        }

        return WrittenDraft(title = title, description = obj.text("description").orEmpty().trim())
    }

    /** ```json … ``` 로 감싸 온 경우 안쪽만 꺼낸다. 감싸지 않았으면 그대로 둔다. */
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

    private fun JsonObject.text(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?.takeIf { it.isNotBlank() }
}
