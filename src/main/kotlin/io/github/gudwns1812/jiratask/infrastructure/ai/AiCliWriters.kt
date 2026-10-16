package io.github.gudwns1812.jiratask.infrastructure.ai

import io.github.gudwns1812.jiratask.domain.commit.CommitDraftOutcome
import io.github.gudwns1812.jiratask.domain.commit.CommitDraftPrompt
import io.github.gudwns1812.jiratask.domain.commit.CommitDraftRequest
import io.github.gudwns1812.jiratask.domain.commit.CommitMessageWriter
import io.github.gudwns1812.jiratask.domain.mr.DraftOutcome
import io.github.gudwns1812.jiratask.domain.mr.DraftRequest
import io.github.gudwns1812.jiratask.domain.mr.DraftWriter
import io.github.gudwns1812.jiratask.domain.mr.MergeRequestDraftPrompt
import java.io.File

class AiCliDraftWriter(
    cli: AiCli,
    workingDirectory: File,
    executablePath: String? = null,
    model: String? = null,
    timeout: Long = AiCliRunner.DEFAULT_TIMEOUT_SECONDS,
    private val runner: AiCliRunner = AiCliRunner(cli, workingDirectory, executablePath, model, timeout),
    private val unreadable: String = AiCliRunner.unreadable(cli),
) : DraftWriter {

    override fun write(request: DraftRequest): DraftOutcome =
        when (val answer = runner.ask(MergeRequestDraftPrompt.stdinOf(request))) {
            is AiAnswer.Said -> AiDraftResponse.parse(answer.text)?.let(DraftOutcome::Written)
                ?: DraftOutcome.Failed(unreadable)
            is AiAnswer.NotAvailable -> DraftOutcome.Unavailable(answer.message)
            is AiAnswer.Failed -> DraftOutcome.Failed(answer.message)
        }
}

class AiCliCommitMessageWriter(
    cli: AiCli,
    workingDirectory: File,
    executablePath: String? = null,
    model: String? = null,
    timeout: Long = AiCliRunner.DEFAULT_TIMEOUT_SECONDS,
    private val runner: AiCliRunner = AiCliRunner(cli, workingDirectory, executablePath, model, timeout),
    private val unreadable: String = AiCliRunner.unreadable(cli),
) : CommitMessageWriter {

    override fun write(request: CommitDraftRequest): CommitDraftOutcome =
        when (val answer = runner.ask(CommitDraftPrompt.stdinOf(request))) {
            is AiAnswer.Said -> AiDraftResponse.commit(answer.text)?.let(CommitDraftOutcome::Written)
                ?: CommitDraftOutcome.Failed(unreadable)
            is AiAnswer.NotAvailable -> CommitDraftOutcome.Unavailable(answer.message)
            is AiAnswer.Failed -> CommitDraftOutcome.Failed(answer.message)
        }
}
