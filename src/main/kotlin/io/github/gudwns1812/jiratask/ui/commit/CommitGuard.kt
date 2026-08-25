package io.github.gudwns1812.jiratask.ui.commit

import io.github.gudwns1812.jiratask.ui.task.TaskAnswer

/** 커밋 메시지에서 이슈 키를 찾아낸다. RULE.md 5.2는 메시지가 작업 키로 시작하도록 정한다. */
object CommitIssueKeys {

    private val ISSUE_KEY = Regex("""\b[A-Z][A-Z0-9_]*-\d+\b""")

    /** 맨 앞의 이슈 키. 없으면 null. */
    fun leading(message: String?): String? =
        message?.trimStart()?.let { text -> ISSUE_KEY.find(text)?.takeIf { it.range.first == 0 }?.value }

    /** 메시지에 등장하는 모든 이슈 키(중복 제거, 등장 순서). */
    fun all(message: String?): List<String> =
        ISSUE_KEY.findAll(message.orEmpty()).map { it.value }.distinct().toList()
}

/**
 * 커밋 직전 검사 결과.
 *
 * **막지 않고 경고한다.** 커밋을 거부하면 오프라인이거나 Jira가 죽었을 때 일을 못 한다.
 * 사용자는 언제나 그대로 커밋할 수 있어야 한다(RULE.md 5.2, 6.1).
 */
sealed interface CommitGuardVerdict {

    /** 경고할 것이 없다. 확인이 끝났거나, 확인할 수 없어 아무 말도 하지 않는 경우. */
    data object Ok : CommitGuardVerdict

    data class Warn(val reason: CommitGuardReason, val message: String) : CommitGuardVerdict
}

enum class CommitGuardReason {
    NO_ISSUE_KEY,
    UNKNOWN_ISSUE_KEY,
    OUTSIDE_SPRINT,
}

/**
 * 커밋 메시지를 RULE.md 5.2에 비추어 본다.
 *
 * 세 가지만 본다 — 키가 있는가, 그 키가 실제로 있는가, 그 작업이 현재 스프린트에 있는가.
 * 그 밖의 것은 판단하지 않는다.
 *
 * **끝난 작업에 커밋하는 것은 경고하지 않는다.** 규칙 위반이 아니고 흔한 일이다(리뷰 지적 반영).
 * **확인할 수 없으면 경고하지 않는다.** 확인할 수 없는 것과 위반한 것은 다르다.
 */
object CommitGuard {

    fun inspect(message: String?, lookup: (String) -> TaskAnswer): CommitGuardVerdict {
        val key = CommitIssueKeys.leading(message)
            ?: return CommitGuardVerdict.Warn(CommitGuardReason.NO_ISSUE_KEY, NO_ISSUE_KEY)

        return when (val answer = lookup(key)) {
            // 물어보지 못했다. 모르는 것을 위반으로 바꾸지 않는다.
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
