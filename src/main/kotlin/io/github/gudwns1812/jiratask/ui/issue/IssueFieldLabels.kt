package io.github.gudwns1812.jiratask.ui.issue

/**
 * 검증 메시지의 필드 경로(`description.deliverables.0`)를 화면의 칸 이름(`결과물 1번째`)으로 바꾼다.
 * 도메인·애플리케이션 계층은 CLI와 같은 경로 문자열을 쓰고, 사람이 읽는 이름은 화면에서만 붙인다.
 */
object IssueFieldLabels {

    private val LABELS = mapOf(
        "type" to "이슈 유형",
        "input" to "입력",
        "title" to "제목",
        "epicKey" to "에픽 키",
        "storyKey" to "스토리 키",
        "branch" to "브랜치",
        "assignee" to "담당자",
        "priority" to "우선순위",
        "storyPoints" to "스토리 포인트",
        "sprint" to "스프린트",
        "dueDate" to "기한",
        "description" to "설명",
        "description.summary" to "설명",
        "description.deliverables" to "결과물",
    )

    private val DELIVERABLE_INDEX = Regex("""^description\.deliverables\.(\d+)$""")

    fun of(path: String): String {
        DELIVERABLE_INDEX.matchEntire(path)?.let { match ->
            val index = match.groupValues[1].toIntOrNull() ?: return@let
            return "결과물 ${index + 1}번째"
        }
        return LABELS[path] ?: path
    }
}
