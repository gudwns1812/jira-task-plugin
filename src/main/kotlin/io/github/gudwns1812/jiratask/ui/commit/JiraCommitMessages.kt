package io.github.gudwns1812.jiratask.ui.commit

import com.intellij.notification.NotificationType
import io.github.gudwns1812.jiratask.application.CreateCommitResult

/**
 * 커밋 메시지에 Jira 이슈 키를 붙이는 규칙.
 *
 * 팀의 커밋 훅이 요구하는 형태는 `[<이슈 키>] <type>: <요약>`이다. 키를 **대괄호로 감싸**
 * 맨 앞에 둔다 — 감싸지 않으면 훅이 커밋을 되돌려보낸다.
 */
object JiraCommitMessages {

    /** 앞에 이미 붙어 있는 키. 대괄호가 있든 없든(예전에 쓰던 형태) 걷어낸다. */
    private val ISSUE_KEY_PREFIX = Regex("""^\[?[A-Z][A-Z0-9_]*-\d+]?\s*""")

    /**
     * [current]가 이미 어떤 이슈 키로 시작하면 그 키를 [issueKey]로 바꾸고, 없으면 앞에 붙인다.
     * 사용자가 이어서 타이핑할 수 있도록 키 뒤에는 공백 하나를 남긴다.
     */
    fun withIssueKey(current: String?, issueKey: String): String {
        val text = current.orEmpty()
        val body = text.replaceFirst(ISSUE_KEY_PREFIX, "").trimStart()

        return if (body.isEmpty()) "[$issueKey] " else "[$issueKey] $body"
    }

    /**
     * AI가 쓴 초안을 커밋 창에 넣을 글로 만든다.
     *
     * **원래 메시지가 이슈 키로 시작했으면 그 키를 남긴다.** 키를 붙이는 것은 도구의 일이고
     * (RULE.md 5.2), 초안 한 번에 사라지면 커밋 직전에야 가드가 "키가 없다"고 경고한다.
     * 초안이 키를 흉내 내 적어 왔더라도 [withIssueKey]가 진짜 키로 갈아 끼운다.
     */
    fun withDraft(current: String?, draft: String): String =
        CommitIssueKeys.leading(current)?.let { withIssueKey(draft, it) } ?: draft.trim()

    /** 이 메시지가 이미 [issueKey]로 시작하는지 — 불필요한 갱신을 막는 데 쓴다. */
    fun startsWith(current: String?, issueKey: String): Boolean {
        val text = current.orEmpty().trimStart()

        return text.startsWith("[$issueKey] ") || text.trim() == "[$issueKey]"
    }
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
