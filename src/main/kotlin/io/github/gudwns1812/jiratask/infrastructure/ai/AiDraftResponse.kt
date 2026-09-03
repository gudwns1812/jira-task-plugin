package io.github.gudwns1812.jiratask.infrastructure.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.gudwns1812.jiratask.domain.commit.CommitType
import io.github.gudwns1812.jiratask.domain.commit.WrittenCommitMessage
import io.github.gudwns1812.jiratask.domain.mr.WrittenDraft

/**
 * 모델이 낸 글을 초안으로 읽는다.
 *
 * 껍질이 두 겹인데 **여기는 안쪽 한 겹만** 맡는다. 바깥 겹(CLI가 씌운 봉투)은 CLI마다 모양이 달라
 * 각 [AiCli]가 벗긴다. 안쪽 겹 — 모델이 낸 JSON과 지시해도 가끔 씌워 오는 코드펜스 — 은
 * 어느 모델이든 똑같아서 한 자리에 둔다.
 *
 * 도메인이 아니라 여기 두는 이유는 도메인이 JDK 말고는 아무것도 import하지 않기 때문이다
 * (ARCHITECTURE.md). JSON 파서를 도메인에 들이지 않는다.
 */
object AiDraftResponse {

    /** 모델이 낸 글에서 제목과 본문을 읽는다. 읽어 낼 수 없으면 null — 규칙 기반 초안이 그대로 남는다. */
    fun parse(text: String?): WrittenDraft? {
        val obj = objectIn(text) ?: return null

        val title = obj.text("title")?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        if (title.isEmpty()) {
            // 제목 없는 초안은 쓸 수 없다. MR 제목은 비울 수 없기 때문이다.
            return null
        }

        return WrittenDraft(title = title, description = obj.text("description").orEmpty().trim())
    }

    /**
     * 모델이 낸 글에서 커밋 타입과 한 줄 요약을 읽는다. MR 초안과 봉투는 같고 열쇠만 다르다.
     *
     * **첫 줄만, 마침표 없이 쓴다.** 지시해도 여러 줄을 내놓거나 본문을 덧붙여 오는 일이 있는데,
     * 팀의 커밋 메시지는 `키 + type: 한 줄`이 전부라(RULE.md 5.2) 그 나머지는 여기서 떨군다.
     *
     * 접두사는 `type`에서 읽되, 요약 앞에 `refactor:`처럼 되풀이해 온 경우도 건져 낸다 —
     * 실제로 자주 그렇게 온다. 어느 쪽에서도 아는 접두사를 찾지 못하면 **초안으로 읽지 않는다.**
     * 아무거나 골라 붙이면 잘못된 분류가 커밋 기록에 그대로 남는다.
     */
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
            // 요약 없는 커밋 메시지는 쓸 수 없다.
            return null
        }

        return WrittenCommitMessage(type, summary)
    }

    private fun objectIn(text: String?): JsonObject? {
        val json = unwrapFence(text.orEmpty()).ifBlank { return null }
        return runCatching { JsonParser.parseString(json) }.getOrNull() as? JsonObject
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

    /**
     * 앞뒤에 다른 말이 섞여 있어도 JSON 객체만 건져 낸다.
     *
     * 봉투를 씌우지 않는 CLI에서 쓰는 뒷받침이다. 우리 프롬프트는 객체 하나만 내라고 지시하므로
     * 첫 `{`부터 마지막 `}`까지가 곧 그 객체다.
     */
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
