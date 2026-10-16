package io.github.gudwns1812.jiratask.infrastructure.ai

import io.github.gudwns1812.jiratask.domain.policy.AiProvider

object AiClis {

    val all: List<AiCli> = listOf(ClaudeCodeCli, CodexCli)

    val default: AiCli = ClaudeCodeCli

    fun of(provider: AiProvider?): AiCli = when (provider) {
        AiProvider.CLAUDE -> ClaudeCodeCli
        AiProvider.CODEX -> CodexCli
        null -> default
    }

    fun byId(id: String?): AiCli = all.firstOrNull { it.id == id } ?: default
}
