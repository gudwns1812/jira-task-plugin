package io.github.gudwns1812.jiratask.domain.mr

/** 브랜치의 커밋 하나. 제목만 쓴다 — 본문은 MR 설명에 넣을 만큼 짧지 않다. */
data class BranchCommit(val subject: String)

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
     * 본문. 연결된 작업과 커밋 목록만 담는다.
     *
     * 이슈 키를 본문에 적어 두면 Jira의 개발 정보 패널과 automation이 MR을 작업에 붙일 수 있다(RULE.md 5.3).
     */
    fun descriptionOf(issueKeys: List<String>, commits: List<BranchCommit>, jiraBaseUrl: String?): String {
        val sections = mutableListOf<String>()

        if (issueKeys.isNotEmpty()) {
            sections += buildString {
                appendLine("### 연결된 작업")
                issueKeys.forEach { key -> appendLine("- ${linkOf(key, jiraBaseUrl)}") }
            }.trimEnd()
        }

        if (commits.isNotEmpty()) {
            sections += buildString {
                appendLine("### 커밋")
                commits.forEach { appendLine("- ${it.subject}") }
            }.trimEnd()
        }

        return sections.joinToString("\n\n")
    }

    private fun linkOf(key: String, jiraBaseUrl: String?): String {
        val base = jiraBaseUrl?.trim()?.trimEnd('/').orEmpty()
        return if (base.isEmpty()) key else "[$key]($base/browse/$key)"
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
