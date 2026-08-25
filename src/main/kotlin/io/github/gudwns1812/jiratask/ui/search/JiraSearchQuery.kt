package io.github.gudwns1812.jiratask.ui.search

import io.github.gudwns1812.jiratask.domain.jira.JiraIssueDetail

/**
 * 검색창에 친 글을 무엇으로 볼 것인가.
 *
 * 이슈 키를 그대로 쳤거나 번호만 쳤으면 그 이슈 하나를 연다. 그 밖에는 제목 검색이다.
 * 번호만 친 경우를 받아 주는 것은 커밋 메시지에서 눈에 익은 형태이기 때문이다.
 */
sealed interface JiraSearchQuery {
    data class ByKey(val issueKey: String) : JiraSearchQuery
    data class ByText(val text: String) : JiraSearchQuery
    data object Empty : JiraSearchQuery

    companion object {
        private val FULL_KEY = Regex("""^[A-Za-z][A-Za-z0-9_]*-\d+$""")
        private val NUMBER = Regex("""^\d+$""")

        fun of(input: String?, projectKey: String?): JiraSearchQuery {
            val text = input?.trim().orEmpty()
            if (text.isEmpty()) {
                return Empty
            }
            if (FULL_KEY.matches(text)) {
                return ByKey(text.uppercase())
            }
            if (NUMBER.matches(text) && !projectKey.isNullOrBlank()) {
                return ByKey("$projectKey-$text")
            }
            return ByText(text)
        }
    }
}

/**
 * 편집 중인 이슈. 제목과 설명만 바꿀 수 있다(RULE.md 3.2, 6.2).
 *
 * 저장에 실패해도 사용자가 친 글은 여기 남아 있다 — 화면을 되돌리지 않는 것이 규칙이다.
 */
data class IssueEdit(
    val original: JiraIssueDetail,
    val summary: String = original.summary,
    val description: String = original.description,
) {

    fun isModified(): Boolean = summary.trim() != original.summary.trim() ||
        description.trim() != original.description.trim()

    /** 저장할 수 없는 이유. 없으면 null. */
    fun problem(): String? = if (summary.isBlank()) BLANK_SUMMARY else null

    fun canSave(): Boolean = isModified() && problem() == null

    /** 저장이 끝났다. 이제 이 값이 원본이다. */
    fun saved(): IssueEdit = IssueEdit(
        original.copy(summary = summary.trim(), description = description.trim()),
        summary.trim(),
        description.trim(),
    )

    companion object {
        const val BLANK_SUMMARY: String = "제목은 비울 수 없습니다."
    }
}
