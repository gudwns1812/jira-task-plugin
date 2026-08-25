package io.github.gudwns1812.jiratask.domain.template

object IssueTemplates {
    val TYPES = listOf("story", "task")

    private val TEMPLATES = mapOf(
        "story" to "예: (S15P11A108-100) [AUTH-001] 회원가입\n\n`feat/100-signup`",
        "task" to "## 설명\n작업 설명을 작성합니다.\n\n## 결과물\n[] 완료 기준 결과물 1\n[] 완료 기준 결과물 2",
    )

    fun get(type: String): String? = TEMPLATES[type]
}
