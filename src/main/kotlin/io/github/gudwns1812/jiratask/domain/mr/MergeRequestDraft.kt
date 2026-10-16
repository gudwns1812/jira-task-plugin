package io.github.gudwns1812.jiratask.domain.mr

import io.github.gudwns1812.jiratask.domain.jira.IssueSummary

object MergeRequestDraft {
    fun titleOf(branch: String?, issueKeys: List<String>, summaryOf: (String) -> String?): String {
        if (issueKeys.size == 1) {
            summaryOf(issueKeys.single())?.takeIf { it.isNotBlank() }
                ?.let { return IssueSummary.withDomainPrefix(it) }
        }
        return humanize(branch)
    }

    fun descriptionOf(issueKeys: List<String>, template: String? = null): String {
        val keys = issueKeys.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val body = template?.trim().orEmpty()

        if (body.isNotEmpty()) {
            return MergeRequestTemplate.fill(body, keys)
        }
        if (keys.isEmpty()) {
            return ""
        }

        return buildString {
            appendLine("### 연결된 작업")
            keys.forEach { appendLine("- $it") }
        }.trimEnd()
    }

    fun withDomain(domain: String?, title: String): String {
        val bare = stripLeading(title)

        return if (domain.isNullOrBlank()) bare else "$domain $bare".trim()
    }

    private val LEADING_BRACKET = Regex("""^\s*\[[^\]]*]\s*""")
    private val LEADING_KEY = Regex("""^\s*[A-Z][A-Z0-9_]*-\d+\s*""")

    private fun stripLeading(title: String): String {
        var text = title.trim()
        while (true) {
            val next = text.replaceFirst(LEADING_BRACKET, "").replaceFirst(LEADING_KEY, "").trim()
            if (next == text) {
                return text
            }
            text = next
        }
    }

    private fun humanize(branch: String?): String {
        val name = branch?.trim().orEmpty().substringAfterLast('/')
        if (name.isEmpty()) {
            return FALLBACK_TITLE
        }
        return name.replace('-', ' ').replace('_', ' ').trim().ifEmpty { FALLBACK_TITLE }
    }

    const val FALLBACK_TITLE: String = "Merge request"
}
