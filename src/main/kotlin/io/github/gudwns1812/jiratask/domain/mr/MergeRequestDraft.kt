package io.github.gudwns1812.jiratask.domain.mr

/**
 * MR 제목과 본문을 **규칙으로** 만든다. AI는 다음 단계다(TASKS.md 6장).
 *
 * 규칙 기반이라도 쓸 만해야 한다는 것이 기준이다 — AI가 실패하거나 꺼져 있어도 MR을 만들 수 있어야 한다.
 */
object MergeRequestDraft {

    /**
     * 제목. 이슈가 하나면 그 이슈를 그대로 쓰고, 여럿이거나 없으면 브랜치 이름을 사람이 읽을 말로 편다.
     *
     * 브랜치 이름에는 이제 이슈 번호가 없으므로(RULE.md 5.1) `feat/signup` 같은 요약이 들어 있다.
     * 그 요약이 곧 제목으로 쓸 만한 말이다.
     */
    fun titleOf(branch: String?, issueKeys: List<String>, summaryOf: (String) -> String?): String {
        if (issueKeys.size == 1) {
            val key = issueKeys.single()
            summaryOf(key)?.takeIf { it.isNotBlank() }?.let { return "$key $it" }
            return key
        }
        return humanize(branch)
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
