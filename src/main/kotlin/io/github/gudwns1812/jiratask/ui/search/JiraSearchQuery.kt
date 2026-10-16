package io.github.gudwns1812.jiratask.ui.search

import io.github.gudwns1812.jiratask.domain.jira.JiraIssueDetail

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

data class IssueEdit(
    val original: JiraIssueDetail,
    val summary: String = original.summary,
    val description: String = original.description,
) {

    fun isModified(): Boolean = summary.trim() != original.summary.trim() ||
        description.trim() != original.description.trim()

    fun problem(): String? = if (summary.isBlank()) BLANK_SUMMARY else null

    fun canSave(): Boolean = isModified() && problem() == null

    fun saved(): IssueEdit = IssueEdit(
        original.copy(summary = summary.trim(), description = description.trim()),
        summary.trim(),
        description.trim(),
    )

    companion object {
        const val BLANK_SUMMARY: String = "제목은 비울 수 없습니다."
    }
}
