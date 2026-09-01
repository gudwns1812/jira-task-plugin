package io.github.gudwns1812.jiratask.domain.mr

/**
 * 팀이 정한 MR 템플릿에 이번 브랜치의 작업 줄을 끼워 넣는다.
 *
 * **템플릿 글자는 지우지 않는다.** 팀이 합의해 둔 양식이고, 우리가 판단할 것은 "작업 목록을 어디에
 * 놓을까"뿐이다. 관련 이슈를 적는 절이 이미 있으면 그 아래에 넣고, 없으면 맨 뒤에 만들어 붙인다.
 */
object MergeRequestTemplate {

    /**
     * 관련 이슈를 적는 절의 제목으로 볼 말들.
     *
     * 너무 넓게 잡지 않는다 — `이슈` 한 글자만 보면 `이슈 재현 방법` 같은 절에도 걸린다.
     */
    private val RELATED_HEADINGS = listOf(
        "관련 이슈", "관련이슈", "연관 이슈", "연관이슈", "연결된 작업", "관련 작업", "관련 지라", "관련 jira",
        "related issue", "related issues", "linked issue", "linked issues", "issues", "jira",
    )

    private val HEADING = Regex("""^\s{0,3}#{1,6}\s+(.*)$""")

    fun fill(template: String, workLines: List<String>): String {
        val bullets = workLines.map { "- $it" }
        if (bullets.isEmpty()) {
            // 적을 작업이 없으면 템플릿만 돌려준다. 빈 절을 만들어 두지 않는다.
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

        // 절 제목 바로 아래에 넣는다. 템플릿이 거기 적어 둔 안내 문구는 그대로 남긴다.
        val filled = lines.toMutableList()
        filled.addAll(headingIndex + 1, bullets)
        return filled.joinToString("\n").trimEnd()
    }

    /** 이 줄이 관련 이슈를 적으라는 제목인가. */
    private fun isRelatedHeading(line: String): Boolean {
        val text = HEADING.matchEntire(line)?.groupValues?.get(1)?.trim()?.lowercase() ?: return false
        val bare = text.trim('#', '*', '_', ':', ' ')

        return RELATED_HEADINGS.any { bare == it || bare.startsWith("$it ") || bare.startsWith("$it:") }
    }

    /** 템플릿에 마땅한 자리가 없을 때 만들어 붙이는 절의 이름. */
    const val APPENDED_HEADING: String = "관련 이슈"
}
