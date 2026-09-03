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

/**
 * MR 제목·본문을 AI에게 맡긴다.
 *
 * 프로세스를 다루는 일은 [AiCliRunner]가, 어느 CLI인지는 [AiCli]가 안다. 여기서는
 * **무엇을 묻고 답을 어떻게 읽는지**만 정한다 — 그래서 provider가 늘어도 이 파일은 그대로다.
 */
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

/**
 * 커밋 메시지를 AI에게 맡긴다.
 *
 * MR 초안과 같은 CLI를 같은 방식으로 부른다. 다른 것은 프롬프트와 응답 모양뿐이라
 * ([CommitDraftPrompt], `type`/`summary`) 그 둘만 여기서 정한다.
 */
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
