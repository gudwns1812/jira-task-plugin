package io.github.gudwns1812.jiratask.ui.commit

import com.intellij.notification.NotificationType
import io.github.gudwns1812.jiratask.application.CreateCommitResult

object JiraCommitMessages {
    private val ISSUE_KEY_PREFIX = Regex("""^\[?[A-Z][A-Z0-9_]*-\d+]?\s*""")

    fun withIssueKey(current: String?, issueKey: String): String {
        val text = current.orEmpty()
        val body = text.replaceFirst(ISSUE_KEY_PREFIX, "").trimStart()

        return if (body.isEmpty()) "[$issueKey] " else "[$issueKey] $body"
    }

    fun withDraft(current: String?, draft: String): String =
        CommitIssueKeys.leading(current)?.let { withIssueKey(draft, it) } ?: draft.trim()

    fun startsWith(current: String?, issueKey: String): Boolean {
        val text = current.orEmpty().trimStart()

        return text.startsWith("[$issueKey] ") || text.trim() == "[$issueKey]"
    }
}

data class CommitReport(val type: NotificationType, val message: String)

object CommitReports {
    fun of(result: CreateCommitResult): CommitReport = when (result) {
        is CreateCommitResult.Success -> CommitReport(
            NotificationType.INFORMATION,
            "커밋했습니다: ${result.commitMessage}",
        )
        is CreateCommitResult.Failure -> CommitReport(NotificationType.ERROR, "커밋 실패: ${result.message}")
    }
}
