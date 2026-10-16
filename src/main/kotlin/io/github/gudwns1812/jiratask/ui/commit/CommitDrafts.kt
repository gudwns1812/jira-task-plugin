package io.github.gudwns1812.jiratask.ui.commit

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import io.github.gudwns1812.jiratask.application.CommitDraftResult
import io.github.gudwns1812.jiratask.application.CommitDraftState
import io.github.gudwns1812.jiratask.application.DraftCommitMessageUseCase
import io.github.gudwns1812.jiratask.infrastructure.GitRepository
import io.github.gudwns1812.jiratask.ui.AiWriters
import io.github.gudwns1812.jiratask.ui.JiraTaskSettings
import java.io.File

class CommitDrafts(
    private val settings: JiraTaskSettings = JiraTaskSettings(),
    private val issueKey: CommitIssueKey = CommitIssueKey(settings),
    private val ai: AiWriters = AiWriters(settings),
) {
    fun useCaseFor(workingDirectory: File): DraftCommitMessageUseCase {
        val chosen = ai.of(workingDirectory) ?: return DraftCommitMessageUseCase(writer = null)

        return DraftCommitMessageUseCase(writer = chosen.commitWriter, sendDiff = chosen.sendDiff)
    }

    fun stateFor(project: Project?, workingDirectory: File, hint: String? = null): CommitDraftState {
        val git = GitRepository(workingDirectory)

        return CommitDraftState(
            issueKey = project?.let(issueKey::issueKeyFor),
            branch = runCatching { git.currentBranch() }.getOrNull(),
            hint = hint?.let(::withoutIssueKey),
            changes = { includeDiff -> git.pendingChanges(includeDiff) },
        )
    }

    fun draft(project: Project?, workingDirectory: File, hint: String? = null): CommitDraftResult =
        useCaseFor(workingDirectory).draft(stateFor(project, workingDirectory, hint))

    companion object {
        fun saveEditedFiles() {
            FileDocumentManager.getInstance().saveAllDocuments()
        }
    }

    private fun withoutIssueKey(hint: String): String? =
        hint.trimStart()
            .let { text -> CommitIssueKeys.leading(text)?.let { text.removePrefix(it) } ?: text }
            .trim()
            .ifEmpty { null }
}
