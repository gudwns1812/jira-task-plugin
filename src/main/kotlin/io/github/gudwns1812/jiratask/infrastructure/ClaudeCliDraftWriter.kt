package io.github.gudwns1812.jiratask.infrastructure

import io.github.gudwns1812.jiratask.domain.commit.CommitDraftOutcome
import io.github.gudwns1812.jiratask.domain.commit.CommitDraftPrompt
import io.github.gudwns1812.jiratask.domain.commit.CommitDraftRequest
import io.github.gudwns1812.jiratask.domain.commit.CommitMessageWriter
import io.github.gudwns1812.jiratask.domain.mr.ClaudeDraftPrompt
import io.github.gudwns1812.jiratask.domain.mr.DraftOutcome
import io.github.gudwns1812.jiratask.domain.mr.DraftRequest
import io.github.gudwns1812.jiratask.domain.mr.DraftWriter
import java.io.File

/**
 * MR 제목·본문을 Claude Code에게 맡긴다.
 *
 * 프로세스를 다루는 일은 [ClaudeCli]가 하고, 여기서는 **무엇을 묻고 답을 어떻게 읽는지**만 정한다.
 */
class ClaudeCliDraftWriter(
    workingDirectory: File,
    executablePath: String? = null,
    model: String? = null,
    timeout: Long = ClaudeCli.DEFAULT_TIMEOUT_SECONDS,
    private val claude: ClaudeCli = ClaudeCli(workingDirectory, executablePath, model, timeout),
) : DraftWriter {

    override fun write(request: DraftRequest): DraftOutcome =
        when (val answer = claude.ask(ClaudeDraftPrompt.stdinOf(request))) {
            is ClaudeAnswer.Said -> ClaudeDraftResponse.parse(answer.text)?.let(DraftOutcome::Written)
                ?: DraftOutcome.Failed(ClaudeCli.UNREADABLE)
            is ClaudeAnswer.NotAvailable -> DraftOutcome.Unavailable(answer.message)
            is ClaudeAnswer.Failed -> DraftOutcome.Failed(answer.message)
        }
}

/**
 * 커밋 메시지를 Claude Code에게 맡긴다.
 *
 * MR 초안과 같은 CLI를 같은 방식으로 부른다. 다른 것은 프롬프트와 응답 모양뿐이라
 * ([CommitDraftPrompt], `subject`/`body`) 그 둘만 여기서 정한다.
 */
class ClaudeCliCommitMessageWriter(
    workingDirectory: File,
    executablePath: String? = null,
    model: String? = null,
    timeout: Long = ClaudeCli.DEFAULT_TIMEOUT_SECONDS,
    private val claude: ClaudeCli = ClaudeCli(workingDirectory, executablePath, model, timeout),
) : CommitMessageWriter {

    override fun write(request: CommitDraftRequest): CommitDraftOutcome =
        when (val answer = claude.ask(CommitDraftPrompt.stdinOf(request))) {
            is ClaudeAnswer.Said -> ClaudeDraftResponse.commit(answer.text)?.let(CommitDraftOutcome::Written)
                ?: CommitDraftOutcome.Failed(ClaudeCli.UNREADABLE)
            is ClaudeAnswer.NotAvailable -> CommitDraftOutcome.Unavailable(answer.message)
            is ClaudeAnswer.Failed -> CommitDraftOutcome.Failed(answer.message)
        }
}
