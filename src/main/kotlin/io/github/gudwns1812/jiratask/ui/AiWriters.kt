package io.github.gudwns1812.jiratask.ui

import io.github.gudwns1812.jiratask.domain.commit.CommitMessageWriter
import io.github.gudwns1812.jiratask.domain.mr.DraftWriter
import io.github.gudwns1812.jiratask.infrastructure.ai.AiCli
import io.github.gudwns1812.jiratask.infrastructure.ai.AiCliCommitMessageWriter
import io.github.gudwns1812.jiratask.infrastructure.ai.AiCliDraftWriter
import io.github.gudwns1812.jiratask.infrastructure.ai.AiClis
import java.io.File

data class ChosenAi(
    val cli: AiCli,
    val draftWriter: DraftWriter,
    val commitWriter: CommitMessageWriter,
    val sendDiff: Boolean,
)

class AiWriters(private val settings: JiraTaskSettings = JiraTaskSettings()) {

    fun of(workingDirectory: File): ChosenAi? {
        val ai = (settings.forAiDraft() as? SettingsLookup.Ready)?.value ?: return null
        val cli = AiClis.of(ai.provider)

        return ChosenAi(
            cli = cli,
            draftWriter = AiCliDraftWriter(
                cli = cli,
                workingDirectory = workingDirectory,
                executablePath = ai.executablePath,
                model = ai.model,
            ),
            commitWriter = AiCliCommitMessageWriter(
                cli = cli,
                workingDirectory = workingDirectory,
                executablePath = ai.executablePath,
                model = ai.model,
            ),
            sendDiff = ai.sendDiff,
        )
    }
}
