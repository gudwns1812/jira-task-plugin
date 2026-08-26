package io.github.gudwns1812.jiratask.domain.navigation

/**
 * 코드·커밋 로그의 이슈 키를 Jira로 잇는 규칙 하나.
 *
 * **설정된 프로젝트 키에만 반응한다.** 아무 대문자-숫자 조합(`UTF-8`, `HTTP-2`, `SHA-256`)에
 * 반응하면 오탐이 심해 아무도 켜 두지 않는다.
 */
data class IssueLinkRule(val issueRegexp: String, val linkRegexp: String) {

    /**
     * 기존 링크 목록에 이 규칙을 반영한다. 우리가 만든 낡은 링크는 걷어내고,
     * 사용자가 손수 넣은 링크는 순서까지 그대로 둔다.
     */
    fun applyTo(existing: List<IssueLinkRule>): List<IssueLinkRule> = removeOursFrom(existing) + this

    companion object {

        /** 우리가 만든 규칙의 생김새. 사용자가 손수 넣은 규칙과 구별하는 데 쓴다. */
        private val OURS = Regex("""^\\b\S+-\\d\+\\b$""")

        private const val LINK_SUFFIX = "/browse/\$0"

        /** 프로젝트 키와 Jira 주소가 모두 있어야 만들 수 있다. 하나라도 없으면 null. */
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

        /**
         * 이 링크를 우리가 만들었는가.
         *
         * 사용자가 손수 넣은 규칙은 지우지 않는다. 그래서 정확히 우리가 쓰는 형태 —
         * `\b<키>-\d+\b` 정규식과 `/browse/$0`로 끝나는 링크 — 일 때만 우리 것으로 본다.
         */
        fun isOurs(issueRegexp: String?, linkRegexp: String?): Boolean =
            OURS.matches(issueRegexp.orEmpty()) && linkRegexp.orEmpty().endsWith(LINK_SUFFIX)

        /**
         * 정규식 메타문자만 골라 이스케이프한다. `Regex.escape`의 `\Q...\E`도 맞게 동작하지만,
         * 이 문자열은 사용자가 `Settings | Version Control | Issue Navigation`에서 읽는다.
         * 정상적인 프로젝트 키(`[A-Z][A-Z0-9_]*`)라면 아무것도 바뀌지 않는다.
         */
        private fun escapeLiteral(text: String): String =
            text.replace(Regex("""[\\.\[\]{}()*+?^$|]""")) { "\\" + it.value }

        /** 설정이 사라졌을 때. 우리 링크만 걷어낸다. */
        fun removeOursFrom(existing: List<IssueLinkRule>): List<IssueLinkRule> =
            existing.filterNot { isOurs(it.issueRegexp, it.linkRegexp) }
    }
}
