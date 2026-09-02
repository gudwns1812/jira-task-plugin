package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.commit.CommitDraftOutcome
import io.github.gudwns1812.jiratask.domain.commit.CommitDraftRequest
import io.github.gudwns1812.jiratask.domain.commit.CommitMessageWriter
import io.github.gudwns1812.jiratask.domain.commit.PendingChanges
import io.github.gudwns1812.jiratask.domain.commit.WrittenCommitMessage

/**
 * 커밋 메시지를 쓰기 전에 알아낸 것들.
 *
 * [changes]가 값이 아니라 함수인 이유는 [BranchState.commitSubjectsSince]와 같다 — git을 부르는
 * 일이라 화면을 그릴 때가 아니라 백그라운드에서 초안을 만들 때 불려야 한다.
 * 인자는 "diff까지 읽을지"다. 설정이 정하므로 유스케이스가 그 값을 넘긴다.
 */
data class CommitDraftState(
    val issueKey: String?,
    val branch: String?,
    /** 사용자가 이미 쓰고 있던 메시지(이슈 키 제외). 힌트로만 쓴다. */
    val hint: String? = null,
    val changes: (Boolean) -> PendingChanges,
)

/** AI에게 커밋 메시지를 맡긴 결과. 거절돼도 사용자는 직접 쓸 수 있다. */
sealed interface CommitDraftResult {
    data class Drafted(val message: WrittenCommitMessage) : CommitDraftResult
    data class Refused(val message: String) : CommitDraftResult
}

/**
 * 커밋할 변경을 재료로 커밋 메시지 초안을 만든다.
 *
 * 이슈 키는 붙이지 않는다 — 붙이는 자리는 커밋 창(RULE.md 5.2)이고, 여기서 붙이면
 * 이미 키가 있는 메시지에 한 번 더 붙는다.
 *
 * **[sendDiff]가 이 유스케이스에서 가장 중요한 값이다.** 꺼져 있으면 변경 내용을 읽지도,
 * 요청에 담지도 않는다. 켜져 있을 때만 소스 코드가 밖으로 나간다.
 */
class DraftCommitMessageUseCase(
    /** 없으면(=AI가 꺼져 있으면) 언제나 거절한다. 그 편이 버튼을 숨기는 것보다 낫다. */
    private val writer: CommitMessageWriter?,
    private val sendDiff: Boolean = false,
) {

    fun draft(state: CommitDraftState): CommitDraftResult {
        val writer = writer ?: return CommitDraftResult.Refused(DISABLED)

        val changes = runCatching { state.changes(sendDiff) }.getOrElse {
            return CommitDraftResult.Refused(NO_GIT)
        }
        if (changes.files.isEmpty()) {
            // 재료가 없으면 부르지 않는다. 빈 재료로 부르면 모델이 그럴듯한 거짓말을 지어낸다.
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
