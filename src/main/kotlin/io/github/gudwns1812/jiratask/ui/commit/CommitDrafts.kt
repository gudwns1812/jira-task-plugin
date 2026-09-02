package io.github.gudwns1812.jiratask.ui.commit

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import io.github.gudwns1812.jiratask.application.CommitDraftResult
import io.github.gudwns1812.jiratask.application.CommitDraftState
import io.github.gudwns1812.jiratask.application.DraftCommitMessageUseCase
import io.github.gudwns1812.jiratask.infrastructure.ClaudeCliCommitMessageWriter
import io.github.gudwns1812.jiratask.infrastructure.GitRepository
import io.github.gudwns1812.jiratask.ui.JiraTaskSettings
import io.github.gudwns1812.jiratask.ui.SettingsLookup
import java.io.File

/**
 * 커밋 메시지 초안을 준비한다. 커밋 창으로 가는 두 경로가 함께 쓴다 —
 * 플러그인의 [CommitMessageDialog]와 IDE 커밋 창의 [DraftCommitMessageAction].
 *
 * 두 창이 같은 재료로 같은 초안을 받게 하려고 한 자리에 둔다. 여기서 갈라지면
 * "커밋 창마다 결과가 다르다"는, 사용자가 원인을 짚을 수 없는 차이가 생긴다.
 */
class CommitDrafts(
    private val settings: JiraTaskSettings = JiraTaskSettings(),
    private val issueKey: CommitIssueKey = CommitIssueKey(settings),
) {

    /**
     * 지금 설정으로 만든 유스케이스.
     *
     * AI가 꺼져 있으면 **writer 없이** 만든다. 버튼을 숨기지 않고 거절 문구를 보여 주기 위해서다 —
     * 숨기면 기능이 있다는 것도, 켜러 가야 한다는 것도 알 길이 없다(MR 창과 같은 판단).
     */
    fun useCaseFor(workingDirectory: File): DraftCommitMessageUseCase {
        val ai = (settings.forAiDraft() as? SettingsLookup.Ready)?.value
            ?: return DraftCommitMessageUseCase(writer = null)

        return DraftCommitMessageUseCase(
            writer = ClaudeCliCommitMessageWriter(
                workingDirectory = workingDirectory,
                executablePath = ai.executablePath,
                model = ai.model,
            ),
            sendDiff = ai.sendDiff,
        )
    }

    /**
     * 초안의 재료. [hint]는 사용자가 이미 쓰고 있던 메시지다.
     *
     * 변경을 읽는 일은 함수로 남겨 둔다 — git을 부르므로 유스케이스가 백그라운드에서 부를 때
     * 실행돼야 하고, diff까지 읽을지는 설정이 정하기 때문이다.
     */
    fun stateFor(project: Project?, workingDirectory: File, hint: String? = null): CommitDraftState {
        val git = GitRepository(workingDirectory)

        return CommitDraftState(
            issueKey = project?.let(issueKey::issueKeyFor),
            branch = runCatching { git.currentBranch() }.getOrNull(),
            hint = hint?.let(::withoutIssueKey),
            changes = { includeDiff -> git.pendingChanges(includeDiff) },
        )
    }

    /** git과 Claude Code를 모두 부른다. **백그라운드에서만** 호출한다. */
    fun draft(project: Project?, workingDirectory: File, hint: String? = null): CommitDraftResult =
        useCaseFor(workingDirectory).draft(stateFor(project, workingDirectory, hint))

    companion object {

        /**
         * 편집기에서 고친 것을 디스크에 내린다. **EDT에서만 부른다.**
         *
         * git은 파일만 본다. 저장하지 않은 편집기 내용은 `git diff`에 잡히지 않아, 방금 고친 코드를
         * 두고도 "커밋할 변경이 없습니다"라는 답을 받는다 — 사용자로서는 도구가 눈이 먼 것으로 보인다.
         * IDE의 커밋 흐름도 커밋 직전에 같은 일을 한다.
         */
        fun saveEditedFiles() {
            FileDocumentManager.getInstance().saveAllDocuments()
        }
    }

    /**
     * 힌트에서 이슈 키를 떼어 낸다. 커밋 창의 메시지는 이미 키로 시작하는데(RULE.md 5.2)
     * 그대로 넘기면 모델이 "이미 이렇게 쓰기로 했구나" 하고 제목에 키를 다시 적는다.
     */
    private fun withoutIssueKey(hint: String): String? =
        hint.trimStart()
            .let { text -> CommitIssueKeys.leading(text)?.let { text.removePrefix(it) } ?: text }
            .trim()
            .ifEmpty { null }
}
