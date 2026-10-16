package io.github.gudwns1812.jiratask.domain.jira

object IssueSummary {
    private val LEADING_BRACKET = Regex("""^\[([^\[\]]*)]\s*""")

    fun withDomainPrefix(summary: String): String {
        val text = summary.trim()
        val bracket = LEADING_BRACKET.find(text) ?: return text
        val code = domainCodeOf(bracket.groupValues[1]) ?: return text

        val rest = text.substring(bracket.range.last + 1)
        return if (rest.isEmpty()) "[$code]" else "[$code] $rest"
    }

    fun domainPrefixOf(summary: String?): String? {
        val text = summary?.trim().orEmpty()
        val bracket = LEADING_BRACKET.find(text) ?: return null

        return domainCodeOf(bracket.groupValues[1])?.let { "[$it]" }
    }

    private fun domainCodeOf(inside: String): String? =
        inside.substringBefore('|').substringBefore('-').trim().ifEmpty { null }
}
