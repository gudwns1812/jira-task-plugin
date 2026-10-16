package io.github.gudwns1812.jiratask.ui.commit

import io.github.gudwns1812.jiratask.ui.task.TaskAnswer

object CommitIssueKeys {

    private val ISSUE_KEY = Regex("""\b[A-Z][A-Z0-9_]*-\d+\b""")

    private val LEADING_ISSUE_KEY = Regex("""^\[?([A-Z][A-Z0-9_]*-\d+)]?""")

    fun leading(message: String?): String? =
        message?.trimStart()?.let { text -> LEADING_ISSUE_KEY.find(text)?.groupValues?.get(1) }

    fun all(message: String?): List<String> =
        ISSUE_KEY.findAll(message.orEmpty()).map { it.value }.distinct().toList()
}

sealed interface CommitGuardVerdict {
    data object Ok : CommitGuardVerdict

    data class Warn(val reason: CommitGuardReason, val message: String) : CommitGuardVerdict
}

enum class CommitGuardReason {
    NO_ISSUE_KEY,
    UNKNOWN_ISSUE_KEY,
    OUTSIDE_SPRINT,
}

object CommitGuard {

    fun inspect(message: String?, lookup: (String) -> TaskAnswer): CommitGuardVerdict {
        val key = CommitIssueKeys.leading(message)
            ?: return CommitGuardVerdict.Warn(CommitGuardReason.NO_ISSUE_KEY, NO_ISSUE_KEY)

        return when (val answer = lookup(key)) {
            is TaskAnswer.Unanswered -> CommitGuardVerdict.Ok
            is TaskAnswer.NotFound ->
                CommitGuardVerdict.Warn(CommitGuardReason.UNKNOWN_ISSUE_KEY, unknownKey(key))
            is TaskAnswer.Known ->
                if (answer.task.inOpenSprint) {
                    CommitGuardVerdict.Ok
                } else {
                    CommitGuardVerdict.Warn(CommitGuardReason.OUTSIDE_SPRINT, outsideSprint(key))
                }
        }
    }

    const val NO_ISSUE_KEY: String =
        "커밋 메시지가 Jira 작업 키로 시작하지 않습니다. 키가 없으면 Jira가 이 커밋을 보지 못합니다."

    fun unknownKey(key: String): String =
        "$key 작업을 Jira에서 찾지 못했습니다. 키를 잘못 적었는지 확인하세요."

    fun outsideSprint(key: String): String =
        "$key 은(는) 현재 스프린트에 없는 작업입니다. 확정된 스프린트 밖의 일을 하고 있는지 확인하세요."
}
