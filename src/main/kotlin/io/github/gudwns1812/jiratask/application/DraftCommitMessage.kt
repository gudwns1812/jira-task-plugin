package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.commit.CommitDraftOutcome
import io.github.gudwns1812.jiratask.domain.commit.CommitDraftRequest
import io.github.gudwns1812.jiratask.domain.commit.CommitMessageWriter
import io.github.gudwns1812.jiratask.domain.commit.PendingChanges
import io.github.gudwns1812.jiratask.domain.commit.WrittenCommitMessage

data class CommitDraftState(
    val issueKey: String?,
    val branch: String?,
    val hint: String? = null,
    val changes: (Boolean) -> PendingChanges,
)

sealed interface CommitDraftResult {
    data class Drafted(val message: WrittenCommitMessage) : CommitDraftResult
    data class Refused(val message: String) : CommitDraftResult
}

class DraftCommitMessageUseCase(
    private val writer: CommitMessageWriter?,
    private val sendDiff: Boolean = false,
) {

    fun draft(state: CommitDraftState): CommitDraftResult {
        val writer = writer ?: return CommitDraftResult.Refused(DISABLED)

        val changes = runCatching { state.changes(sendDiff) }.getOrElse {
            return CommitDraftResult.Refused(NO_GIT)
        }
        if (changes.files.isEmpty()) {
            return CommitDraftResult.Refused(NO_CHANGES)
        }

        val request = CommitDraftRequest(
            issueKey = state.issueKey,
            branch = state.branch,
            changedFiles = changes.files,
            diff = changes.diff.takeIf { sendDiff && it.isNotBlank() },
            hint = state.hint,
        )

        val outcome = runCatching { writer.write(request) }
            .getOrElse { CommitDraftOutcome.Failed(FAILED) }

        return when (outcome) {
            is CommitDraftOutcome.Written -> CommitDraftResult.Drafted(outcome.message)
            is CommitDraftOutcome.Unavailable -> CommitDraftResult.Refused(outcome.message)
            is CommitDraftOutcome.Failed -> CommitDraftResult.Refused(outcome.message)
        }
    }

    companion object {
        const val DISABLED: String = "AI 초안이 꺼져 있습니다. 설정에서 켜세요."
        const val FAILED: String = "AI 초안을 만들지 못했습니다."
        const val NO_CHANGES: String = "커밋할 변경이 없습니다. 파일을 고치거나 스테이지한 뒤에 다시 눌러 주세요."
        const val NO_GIT: String = "변경 내용을 읽지 못했습니다. Git 저장소인지 확인하세요."
    }
}
