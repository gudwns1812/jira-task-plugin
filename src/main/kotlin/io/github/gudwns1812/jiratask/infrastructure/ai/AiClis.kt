package io.github.gudwns1812.jiratask.infrastructure.ai

import io.github.gudwns1812.jiratask.domain.policy.AiProvider

/**
 * 이 플러그인이 부를 줄 아는 AI CLI들.
 *
 * 새 provider를 붙이는 일은 [AiCli] 하나를 구현해 여기 더하는 일이 되어야 한다. 목록을 한 자리에
 * 두는 이유는 설정 화면이 이것을 그대로 훑어 그리기 때문이다 — 목록과 화면이 갈라지면 코드에는
 * 있는데 고를 수 없는 provider가 생긴다.
 */
object AiClis {

    val all: List<AiCli> = listOf(ClaudeCodeCli, CodexCli)

    /** 예전 설정 파일에는 provider가 없다. 그 파일들은 모두 Claude Code를 쓰고 있었다. */
    val default: AiCli = ClaudeCodeCli

    fun of(provider: AiProvider?): AiCli = when (provider) {
        AiProvider.CLAUDE -> ClaudeCodeCli
        AiProvider.CODEX -> CodexCli
        null -> default
    }

    fun byId(id: String?): AiCli = all.firstOrNull { it.id == id } ?: default
}
