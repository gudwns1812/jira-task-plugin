package io.github.gudwns1812.jiratask.domain.jira

/**
 * 작업 제목 앞의 대괄호를 **도메인 코드 하나로** 줄인다.
 *
 * 작업 제목은 기능 구현이면 기능 ID를, 그 밖이면 업무 분류를 앞에 단다(RULE.md 1.3). 거기 붙던
 * 일련번호와 담당 영역은 MR 제목에서 읽을 이유가 없다 — 어느 작업인지는 앞의 이슈 키가 이미 말하고,
 * 누가 하는 일인지는 담당자가 말한다. MR 목록에서 눈에 남아야 하는 것은 **어느 도메인의 일인가**다.
 *
 * ```
 * [AUTH-001] 회원가입 화면 구현      → [AUTH] 회원가입 화면 구현
 * [AUTH-001 | FE] 회원가입 화면 구현 → [AUTH] 회원가입 화면 구현
 * [INFRA | BE] CI/CD 파이프라인 구축 → [INFRA] CI/CD 파이프라인 구축
 * [PLAN] 서비스 목표 정의            → [PLAN] 서비스 목표 정의
 * ```
 *
 * **알아보지 못한 제목은 손대지 않는다.** 규칙 밖의 제목은 늘 섞여 있고(규칙이 바뀌기 전에 만든 이슈,
 * 외부에서 만든 이슈), 그런 제목까지 잘라내면 사람이 적어 둔 말이 사라진다. 제목을 Jira에 되쓰지
 * 않으므로(ARCHITECTURE.md 6.2 "제목 검증을 하지 않는다") 여기서 틀려도 원본은 그대로 남지만,
 * 화면에 보이는 제목이 원본과 달라지는 일은 적을수록 좋다.
 */
object IssueSummary {

    /** 제목 맨 앞의 대괄호 한 쌍과 그 뒤 공백. 중첩된 대괄호는 규칙에 없으므로 보지 않는다. */
    private val LEADING_BRACKET = Regex("""^\[([^\[\]]*)]\s*""")

    fun withDomainPrefix(summary: String): String {
        val text = summary.trim()
        val bracket = LEADING_BRACKET.find(text) ?: return text
        val code = domainCodeOf(bracket.groupValues[1]) ?: return text

        val rest = text.substring(bracket.range.last + 1)
        return if (rest.isEmpty()) "[$code]" else "[$code] $rest"
    }

    /**
     * 대괄호 안에서 도메인 코드만 남긴다. `|` 뒤의 담당 영역과 `-` 뒤의 일련번호를 뗀다.
     *
     * 남는 것이 없으면 null이다 — `[]`나 `[ | FE]` 같은 제목을 `[]`로 바꿔 놓지 않는다.
     */
    private fun domainCodeOf(inside: String): String? =
        inside.substringBefore('|').substringBefore('-').trim().ifEmpty { null }
}
