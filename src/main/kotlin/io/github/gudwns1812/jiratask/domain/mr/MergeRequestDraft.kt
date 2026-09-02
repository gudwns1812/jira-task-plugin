package io.github.gudwns1812.jiratask.domain.mr

/**
 * MR 제목과 본문을 **규칙으로** 만든다. AI는 다음 단계다(TASKS.md 6장).
 *
 * 규칙 기반이라도 쓸 만해야 한다는 것이 기준이다 — AI가 실패하거나 꺼져 있어도 MR을 만들 수 있어야 한다.
 */
object MergeRequestDraft {

    /**
     * 제목. 이슈가 하나면 **Jira 작업 제목을 그대로** 쓰고, 여럿이거나 없으면 태그만 앞에 붙여
     * 브랜치 이름을 사람이 읽을 말로 편다.
     *
     * 이슈 키는 제목에 넣지 않는다. Jira가 이 MR을 작업에 붙이는 근거는 본문의 키이고,
     * 제목에까지 키를 넣으면 GitLab MR 목록이 번호로 뒤덮여 무슨 일인지 읽히지 않는다.
     *
     * 대신 `[INFRA | BE]` 같은 태그를 남긴다 — 팀의 Jira 작업 제목이 이미 그 형태이고
     * (RULE.md "기능 구현 작업 예시"), MR 목록에서 어느 영역의 일인지 한눈에 갈라 보이게 한다.
     */
    fun titleOf(branch: String?, issueKeys: List<String>, summaryOf: (String) -> String?): String {
        val summaries = issueKeys.mapNotNull { key -> summaryOf(key)?.trim()?.ifEmpty { null } }

        // 작업 하나짜리 MR이면 그 작업 제목이 곧 이 MR이 하는 일이다. 글은 그대로 쓰고
        // 앞의 대괄호만 다시 붙인다 — 기능 ID를 떼어낸 태그로.
        if (issueKeys.size == 1 && summaries.size == 1) {
            val summary = summaries.single()
            return withTag(tagOf(summary), summary)
        }

        return withTag(summaries.firstNotNullOfOrNull(::tagOf), humanize(branch))
    }

    /**
     * Jira 작업 제목 맨 앞의 대괄호에서 **영역·분류만** 뽑는다.
     *
     * ```
     * [INFRA | BE] CI/CD 파이프라인 구축      → [INFRA | BE]
     * [AUTH-001 | FE] 회원가입 API 구현       → [FE]
     * [AUTH-001] 회원가입                     → [AUTH]
     * [PLAN] 서비스 목표 정의                 → [PLAN]
     * ```
     *
     * **기능 ID는 뺀다.** `AUTH-001`은 Jira 안에서 스토리를 가리키는 번호라 MR 목록에서는
     * 읽는 사람에게 아무것도 알려주지 않는다. 남기려는 것은 "어느 영역의 일인가" 하나다.
     *
     * 태그를 **읽어 오기만 하고 지어내지 않는다.** 영역을 잘못 붙인 MR은 아무도 고쳐 주지 않고
     * 목록에서 계속 잘못된 자리에 서 있게 된다.
     */
    fun tagOf(summary: String?): String? {
        val text = summary?.trim().orEmpty()
        if (!text.startsWith("[")) {
            return null
        }

        val end = text.indexOf(']')
        if (end < 0) {
            return null
        }

        val inside = text.substring(1, end).split('|').map { it.trim() }.filter { it.isNotEmpty() }
        val parts = inside.filterNot(FEATURE_ID::matches).ifEmpty {
            // 영역이 안 적힌 작업이다. 번호만 떼고 앞의 이름은 남긴다 — `AUTH-001` → `AUTH`.
            // 어느 기능 갈래의 일인지는 그 이름이 알려 준다(RULE.md "기능 목록").
            inside.map { it.substringBeforeLast('-') }.filter { it.isNotEmpty() }
        }

        return parts.takeIf { it.isNotEmpty() }?.joinToString(" | ", prefix = "[", postfix = "]")
    }

    /**
     * 태그를 앞에 붙인다. 이미 앞에 있던 대괄호나 이슈 키는 걷어낸다 —
     * AI가 제목을 다시 쓸 때 흉내 내어 붙여 오는 일이 있어서, 두 번 붙는 것을 여기서 막는다.
     */
    fun withTag(tag: String?, title: String): String {
        val bare = stripLeading(title)

        return if (tag.isNullOrBlank()) bare else "$tag $bare".trim()
    }

    /** `AUTH-001`, `S15P21A403-19` 같은 번호. 태그 자리에서 걷어낸다. */
    private val FEATURE_ID = Regex("""^[A-Z][A-Z0-9_]*-\d+$""")

    private val LEADING_TAG = Regex("""^\s*\[[^\]]*]\s*""")
    private val LEADING_KEY = Regex("""^\s*[A-Z][A-Z0-9_]*-\d+\s*""")

    private fun stripLeading(title: String): String {
        var text = title.trim()
        while (true) {
            val next = text.replaceFirst(LEADING_TAG, "").replaceFirst(LEADING_KEY, "").trim()
            if (next == text) {
                return text
            }
            text = next
        }
    }

    /**
     * 본문. **작업 키만** 줄줄이 적는다.
     *
     * 커밋 제목을 붙이지 않는 이유는 읽는 사람이 여기서 찾는 것이 "어느 작업인가" 하나뿐이기
     * 때문이다. 무슨 일을 했는지는 MR의 커밋 목록이 이미 보여 준다. 본문에 적힌 키 문자열이
     * Jira가 이 MR을 작업에 붙이는 근거다(RULE.md 5.3).
     */
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

    /** `feat/live-chat` → `live chat`. 접두와 구분자를 걷어내 읽을 수 있는 말로 만든다. */
    private fun humanize(branch: String?): String {
        val name = branch?.trim().orEmpty().substringAfterLast('/')
        if (name.isEmpty()) {
            return FALLBACK_TITLE
        }
        return name.replace('-', ' ').replace('_', ' ').trim().ifEmpty { FALLBACK_TITLE }
    }

    const val FALLBACK_TITLE: String = "Merge request"
}
