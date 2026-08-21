package io.github.gudwns1812.jiratask.ui.commit

import com.intellij.notification.NotificationType
import io.github.gudwns1812.jiratask.application.CreateCommitResult

/** 커밋 메시지에 Jira 이슈 키를 붙이는 규칙. */
object JiraCommitMessages {

    private val ISSUE_KEY_PREFIX = Regex("""^[A-Z][A-Z0-9_]*-\d+\s*""")

    /**
     * [current]가 이미 어떤 이슈 키로 시작하면 그 키를 [issueKey]로 바꾸고, 없으면 앞에 붙인다.
     * 사용자가 이어서 타이핑할 수 있도록 키 뒤에는 공백 하나를 남긴다.
     */
    fun withIssueKey(current: String?, issueKey: String): String {
        val text = current.orEmpty()
        val body = text.replaceFirst(ISSUE_KEY_PREFIX, "").trimStart()

        return if (body.isEmpty()) "$issueKey " else "$issueKey $body"
    }

    /** 이 메시지가 이미 [issueKey]로 시작하는지 — 불필요한 갱신을 막는 데 쓴다. */
    fun startsWith(current: String?, issueKey: String): Boolean =
        current.orEmpty().trimStart().startsWith("$issueKey ") || current.orEmpty().trim() == issueKey
}

data class CommitReport(val type: NotificationType, val message: String)

/** [CreateCommitResult]를 알림 한 줄로 옮긴다. CLI가 콘솔에 찍던 문구와 같은 내용이다. */
object CommitReports {
    fun of(result: CreateCommitResult): CommitReport = when (result) {
        is CreateCommitResult.Success -> CommitReport(
            NotificationType.INFORMATION,
            "커밋했습니다: ${result.commitMessage}",
        )
        is CreateCommitResult.Failure -> CommitReport(NotificationType.ERROR, "커밋 실패: ${result.message}")
    }
}
