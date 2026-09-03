package io.github.gudwns1812.jiratask.infrastructure.ai

import java.io.File

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Claude Code CLI.
 *
 * `--bare`는 쓰지 않는다. OAuth와 키체인을 읽지 않아 구독 인증을 건너뛴다 — API 키가 없는 사용자는
 * 그대로 거부당한다.
 */
object ClaudeCodeCli : AiCli {

    private const val HOME_DIRECTORY = ".claude"

    /** `~/.claude/settings.json`의 `"model"`. 없으면 CLI가 자기 기본값을 쓴다. */
    private val MODEL_KEY = Regex(""""model"\s*:\s*"(.+?)"""")

    override val id: String = "claude"

    override val displayName: String = "Claude Code"

    /**
     * 별칭은 늘 그 계열의 최신 모델을 가리키므로 버전을 박아 두는 것보다 낫다.
     *
     * `claude --help`가 밝힌 별칭 셋(`fable`·`opus`·`sonnet`)에 전체 이름을 더한다. CLI는
     * "별칭이나 모델의 전체 이름"을 받으므로 둘 다 통한다. 없는 별칭을 지어내지는 않는다 —
     * 사용자가 고르기만 했는데 실행에서 거부당하면 안 된다.
     */
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

    /**
     * Windows에서 PATH의 `claude`는 실행할 수 없는 sh 스크립트다 — npm이 함께 깐 `claude.cmd`가
     * 진짜 실행 파일이다. `ProcessBuilder`는 `git.exe`처럼 확장자를 알아서 붙여 주지 않으므로
     * 여기서 직접 고른다.
     */
    override fun binaryNames(os: OperatingSystem): List<String> = when (os) {
        OperatingSystem.WINDOWS -> listOf("claude.cmd", "claude.exe", "claude")
        else -> listOf("claude")
    }

    override fun isSessionVariable(name: String): Boolean =
        name == "CLAUDECODE" || name.startsWith("CLAUDE_CODE_")

    /**
     * **도구를 막고 부른다**(`--restricted`) — 글만 써 달라는 일이라 명령 실행도 WebFetch도 필요 없다.
     *
     * 그 옵션을 모르는 낮은 버전이 있다. 그때 옵션을 빼고 다시 부르지 않는다 — 사용자 저장소에서
     * 명령을 돌릴 수 있는 채로 모델을 부르는 것은 초안 하나와 바꿀 일이 아니다. 대신 [outdated]가
     * 버전을 올리라고 알린다.
     */
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

    /**
     * 낮은 버전이라 거절당했으면 무엇을 하면 되는지. 아니면 null.
     *
     * CLI가 하는 말은 `unknown option '--restricted'`뿐이라, 그대로 올리면 사용자는 우리 코드가
     * 잘못됐다고 읽는다. 실제로 할 일은 CLI를 올리는 것 하나다.
     */
    fun outdated(result: ProcessResult): String? {
        val said = result.stderr + result.stdout
        if (!said.contains(RESTRICTED) || !said.contains("unknown option")) {
            return null
        }

        return OUTDATED
    }

    /**
     * `--output-format json`의 봉투에서 모델이 낸 글만 꺼낸다.
     *
     * **줄 단위로 찾는다.** stdout에는 봉투 말고도 다른 줄이 섞여 나온다 — 실제로
     * `Client.listTools() called but server does not advertise tools capability` 같은
     * MCP 서버 경고가 함께 찍힌다. 통째로 파싱하면 그 한 줄 때문에 전부 실패한다.
     */
    fun textOf(cliOutput: String): String? {
        val envelope = envelopeIn(cliOutput)

        if (envelope != null) {
            // CLI가 오류를 봉투에 담아 주면 그것을 그대로 위로 올린다.
            // **봉투를 알아본 이상 원본으로 되돌아가지 않는다.** 답이 비어 있으면 그건 실패다 —
            // 되돌아가면 봉투 JSON이 통째로 커밋 메시지 칸에 들어간다(실제로 그렇게 됐다).
            return envelope.text("result") ?: envelope.text("error")
        }

        // 봉투를 못 찾았다. 모델이 낸 JSON이 바로 왔을 수도 있으니 한 번은 더 읽어 본다.
        return cliOutput.trim().ifEmpty { null }
    }

    /**
     * 봉투가 실패를 담고 있으면 그 문구. 실패가 아니면 null.
     *
     * Claude Code는 로그인이 끊겨도 **종료 코드 0으로** 끝나고 봉투 안에만 이유를 적는다
     * (`is_error: true`, `subtype`이 `error_`로 시작). 그 말을 그대로 올려야 사용자가
     * `claude /login`을 해야 한다는 것을 안다.
     */
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

    /**
     * 이 JSON이 CLI가 씌운 봉투인가.
     *
     * `result`·`error` 말고 `session_id`도 본다 — 답이 비어 있으면 `result` 키 자체가 없을 수 있고,
     * 그때도 이것은 분명히 봉투다. 모델이 낸 JSON에는 저런 열쇠가 없다.
     */
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
