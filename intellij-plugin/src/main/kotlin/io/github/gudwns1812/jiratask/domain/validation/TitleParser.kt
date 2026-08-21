package io.github.gudwns1812.jiratask.domain.validation

sealed class ParsedEpicTitle {
    data class Feature(val domainCode: String, val summary: String) : ParsedEpicTitle()
    data class Support(val summary: String) : ParsedEpicTitle()
}

data class ParsedStoryTitle(
    val domainCode: String,
    val sequence: String,
    val summary: String,
)

sealed class ParsedTaskTitle {
    data class Feature(
        val domainCode: String,
        val sequence: String,
        val area: String,
        val summary: String,
    ) : ParsedTaskTitle()

    data class Common(
        val category: String,
        val area: String?,
        val summary: String,
    ) : ParsedTaskTitle()
}

object TitleParser {
    private val EPIC_FEATURE_TITLE = Regex("""\[([A-Z][A-Z0-9]*)] (.+)""")
    private val EPIC_SUPPORT_TITLE = Regex("""[^\[\]].+""")
    private val STORY_TITLE = Regex("""\[([A-Z][A-Z0-9]*)-(\d+)] (.+)""")
    private val TASK_FEATURE_TITLE = Regex("""\[([A-Z][A-Z0-9]*)-(\d+) \| ([^\]]+)] (.+)""")
    private val TASK_COMMON_TITLE = Regex("""\[([A-Z]+)(?: \| ([^\]]+))?] (.+)""")

    fun parseEpicTitle(title: String): ParsedEpicTitle? {
        val featureMatch = EPIC_FEATURE_TITLE.matchEntire(title)
        if (featureMatch != null) {
            val (domainCode, summary) = featureMatch.destructured
            return ParsedEpicTitle.Feature(domainCode, summary)
        }

        if (EPIC_SUPPORT_TITLE.matches(title)) {
            return ParsedEpicTitle.Support(title)
        }

        return null
    }

    fun parseStoryTitle(title: String): ParsedStoryTitle? {
        val match = STORY_TITLE.matchEntire(title) ?: return null
        val (domainCode, sequence, summary) = match.destructured
        return ParsedStoryTitle(domainCode, sequence, summary)
    }

    fun parseTaskTitle(title: String): ParsedTaskTitle? {
        val featureMatch = TASK_FEATURE_TITLE.matchEntire(title)
        if (featureMatch != null) {
            val (domainCode, sequence, area, summary) = featureMatch.destructured
            return ParsedTaskTitle.Feature(domainCode, sequence, area, summary)
        }

        val commonMatch = TASK_COMMON_TITLE.matchEntire(title)
        if (commonMatch != null) {
            val category = commonMatch.groupValues[1]
            val area = commonMatch.groups[2]?.value
            val summary = commonMatch.groupValues[3]
            return ParsedTaskTitle.Common(category, area, summary)
        }

        return null
    }
}
