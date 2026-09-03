package io.github.gudwns1812.jiratask.infrastructure.ai

import java.io.File

/**
 * OpenAI Codex CLI.
 *
 * Claude Code와 같은 이유로 API 키를 받지 않는다 — `codex login`으로 마쳐 둔 ChatGPT 구독 인증을
 * 그대로 탄다. 플러그인은 여기서도 어떤 자격 증명도 갖지 않는다.
 */
object CodexCli : AiCli {

    private const val HOME_DIRECTORY = ".codex"

    /** `~/.codex/config.toml` 맨 앞의 `model = "..."`. 프로필 안의 것은 보지 않는다. */
    private val MODEL_LINE = Regex("""^\s*model\s*=\s*["'](.+?)["']""", RegexOption.MULTILINE)

    override val id: String = "codex"

    override val displayName: String = "Codex"

    /**
     * Codex에서 고를 수 있는 현재 GPT-5.6 계열이다.
     *
     * `gpt-5.6`은 Sol 별칭이지만, Codex 설정에 별칭을 쓴 사용자도 같은 이름을 골라 유지할 수
     * 있게 함께 보인다. CLI 설정 파일의 모델은 이 목록보다 먼저 보이므로, 계정별로 허용된 다른
     * 모델도 잃지 않는다.
     */
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

    /**
     * **`CODEX_HOME`은 지우지 않는다.** 그 안에 로그인 정보가 있어서, 지우면 구독 인증을 잃고
     * 사용자는 이유 없이 로그인을 다시 하라는 말을 듣는다. 끊어야 하는 것은 "지금 샌드박스 안이다"를
     * 알리는 표시뿐이다.
     */
    override fun isSessionVariable(name: String): Boolean = name.startsWith("CODEX_SANDBOX")

    /**
     * `codex exec`로 비대화형 호출을 만든다.
     *
     * - 프롬프트는 인자가 아니라 **stdin**으로 넘긴다. 위치 인자를 `-`로 두면 stdin에서 읽는다.
     *   Claude 쪽과 같은 이유다 — Windows에서 인자 안의 줄바꿈이 명령줄을 끊는다.
     * - 답은 stdout이 아니라 [OUTPUT_LAST_MESSAGE]가 가리킨 파일에서 읽는다. `--json`의 이벤트
     *   스키마를 따라가는 것보다 훨씬 덜 깨진다 — 그쪽은 버전마다 이벤트 이름이 움직인다.
     * - 저장소 밖에서도 부를 수 있어야 하므로 git 검사를 건너뛴다.
     *
     * [SANDBOX]는 글만 쓰게 하려는 것이다(Claude의 `--restricted`에 해당). **이 플래그만은
     * `codex exec --help`로 확인해야 한다** — 거부당하면 종료 코드가 0이 아니고 Codex가 한 말이
     * 그대로 창에 올라오므로, 실패하더라도 규칙 기반 초안은 남고 원인은 화면에 보인다.
     */
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
            // 위치 인자 `-`는 "프롬프트를 stdin에서 읽으라"는 뜻이다. 반드시 맨 뒤에 둔다.
            add("-")
        }

        return Invocation(
            command = command,
            readText = { result -> textOf(lastMessage, result.stdout) },
            cleanUp = { lastMessage.delete() },
        )
    }

    /**
     * 최종 답. 파일이 비어 있으면 stdout에서 건져 낸다.
     *
     * 뒷받침을 두는 이유는 [OUTPUT_LAST_MESSAGE]가 없는 낡은 버전이 있을 수 있기 때문이다.
     * 그때 stdout에는 진행 로그가 함께 찍히지만, 우리 프롬프트는 JSON 객체 하나만 내라고 지시하므로
     * 첫 `{`부터 마지막 `}`까지를 꺼내면 된다.
     */
    fun textOf(lastMessage: File, stdout: String): String? {
        val written = runCatching { lastMessage.readText(Charsets.UTF_8) }.getOrDefault("")
        return written.trim().ifEmpty { null } ?: AiDraftResponse.objectBlockIn(stdout)
    }

    const val SKIP_GIT_REPO_CHECK: String = "--skip-git-repo-check"
    const val OUTPUT_LAST_MESSAGE: String = "--output-last-message"

    /** 글만 쓰는 일이라 쓰기 권한이 필요 없다. */
    val SANDBOX: List<String> = listOf("--sandbox", "read-only")
}
