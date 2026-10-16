package io.github.gudwns1812.jiratask.domain.mr

object MergeRequestTemplate {
    private val RELATED_HEADINGS = listOf(
        "관련 이슈", "관련이슈", "연관 이슈", "연관이슈", "연결된 작업", "관련 작업", "관련 지라", "관련 jira",
        "related issue", "related issues", "linked issue", "linked issues", "issues", "jira",
    )

    private val HEADING = Regex("""^\s{0,3}#{1,6}\s+(.*)$""")

    fun fill(template: String, workLines: List<String>): String {
        val bullets = workLines.map { "- $it" }
        if (bullets.isEmpty()) {
            return template.trimEnd()
        }

        val lines = template.lines()
        val headingIndex = lines.indexOfFirst { isRelatedHeading(it) }

        if (headingIndex < 0) {
            return buildString {
                appendLine(template.trimEnd())
                appendLine()
                appendLine("## $APPENDED_HEADING")
                bullets.forEach { appendLine(it) }
            }.trimEnd()
        }

        val filled = lines.toMutableList()
        filled.addAll(headingIndex + 1, bullets)
        return filled.joinToString("\n").trimEnd()
    }

    private fun isRelatedHeading(line: String): Boolean {
        val text = HEADING.matchEntire(line)?.groupValues?.get(1)?.trim()?.lowercase() ?: return false
        val bare = text.trim('#', '*', '_', ':', ' ')

        return RELATED_HEADINGS.any { bare == it || bare.startsWith("$it ") || bare.startsWith("$it:") }
    }

    const val APPENDED_HEADING: String = "관련 이슈"
}
