package io.github.gudwns1812.jiratask.domain.navigation

data class IssueLinkRule(val issueRegexp: String, val linkRegexp: String) {
    fun applyTo(existing: List<IssueLinkRule>): List<IssueLinkRule> = removeOursFrom(existing) + this

    companion object {
        private val OURS = Regex("""^\\b\S+-\\d\+\\b$""")

        private const val LINK_SUFFIX = "/browse/\$0"

        fun of(projectKey: String?, jiraBaseUrl: String?): IssueLinkRule? {
            val key = projectKey?.trim().orEmpty()
            val base = jiraBaseUrl?.trim()?.trimEnd('/').orEmpty()
            if (key.isEmpty() || base.isEmpty()) {
                return null
            }

            return IssueLinkRule(
                issueRegexp = """\b${escapeLiteral(key)}-\d+\b""",
                linkRegexp = base + LINK_SUFFIX,
            )
        }

        fun isOurs(issueRegexp: String?, linkRegexp: String?): Boolean =
            OURS.matches(issueRegexp.orEmpty()) && linkRegexp.orEmpty().endsWith(LINK_SUFFIX)

        private fun escapeLiteral(text: String): String =
            text.replace(Regex("""[\\.\[\]{}()*+?^$|]""")) { "\\" + it.value }

        fun removeOursFrom(existing: List<IssueLinkRule>): List<IssueLinkRule> =
            existing.filterNot { isOurs(it.issueRegexp, it.linkRegexp) }
    }
}
