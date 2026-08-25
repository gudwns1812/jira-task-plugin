package io.github.gudwns1812.jiratask.domain.policy

/** RULE.md를 코드로 옮긴 업무 정책이다. */
data class WorkPolicy(
    val areas: List<String>,
    val workCategories: List<String>,
    val storyPoints: List<Int>,
    val labels: Labels,
) {
    data class Labels(
        val areas: Map<String, String>,
        val categories: Map<String, String>,
    )

    fun isArea(value: String): Boolean = areas.contains(value)

    fun isWorkCategory(value: String): Boolean = workCategories.contains(value)

    fun labelForArea(area: String): String = labels.areas[area] ?: area

    fun labelForCategory(category: String): String? = labels.categories[category]

    companion object {
        val DEFAULT: WorkPolicy = WorkPolicy(
            areas = listOf("FE", "BE", "AI", "QA", "INFRA"),
            workCategories = listOf("PLAN", "UX", "DESIGN", "TECH", "INFRA", "DOCS", "QA", "PT"),
            storyPoints = listOf(1, 2, 3, 5, 8),
            labels = Labels(
                areas = mapOf("INFRA" to "Infra"),
                categories = mapOf(
                    "PLAN" to "기획",
                    "UX" to "기획",
                    "DESIGN" to "기획",
                    "INFRA" to "Infra",
                    "DOCS" to "Docs",
                    "QA" to "QA",
                ),
            ),
        )
    }
}
