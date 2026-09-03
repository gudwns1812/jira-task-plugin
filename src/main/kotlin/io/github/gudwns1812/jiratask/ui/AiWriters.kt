package io.github.gudwns1812.jiratask.ui

import io.github.gudwns1812.jiratask.domain.commit.CommitMessageWriter
import io.github.gudwns1812.jiratask.domain.mr.DraftWriter
import io.github.gudwns1812.jiratask.infrastructure.ai.AiCli
import io.github.gudwns1812.jiratask.infrastructure.ai.AiCliCommitMessageWriter
import io.github.gudwns1812.jiratask.infrastructure.ai.AiCliDraftWriter
import io.github.gudwns1812.jiratask.infrastructure.ai.AiClis
import java.io.File

/** 설정에서 고른 AI와, 그 AI로 만든 초안 작성자들. */
data class ChosenAi(
    val cli: AiCli,
    val draftWriter: DraftWriter,
    val commitWriter: CommitMessageWriter,
    /** 커밋 초안에 변경 내용(diff)까지 보낼지. 커밋 창에서만 본다. */
    val sendDiff: Boolean,
)

/**
 * 설정을 읽어 초안 작성자를 만든다.
 *
 * MR 창과 커밋 창이 **똑같은 판단을 따로** 하던 자리다. 갈라진 채로 두면 provider가 늘 때마다 두
 * 곳을 같이 고쳐야 하고, 한쪽만 고치면 "MR은 Codex로 쓰는데 커밋은 Claude로 쓴다"는, 사용자가
 * 원인을 짚을 수 없는 어긋남이 남는다.
 *
 * AI가 꺼져 있으면 null이다. **버튼을 숨기는 데 쓰지 않는다** — 부르는 쪽은 그때도 버튼을 보이고
 * 누르면 "설정에서 켜세요"라고 답한다.
 */
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
