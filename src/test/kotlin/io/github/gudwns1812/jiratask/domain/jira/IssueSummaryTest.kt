package io.github.gudwns1812.jiratask.domain.jira

import org.junit.Assert.assertEquals
import org.junit.Test

/** 작업 제목 앞의 대괄호를 도메인 코드로 줄이는 규칙(RULE.md 1.3). */
class IssueSummaryTest {

    @Test
    fun `drops the sequence number from a feature id`() {
        assertEquals(
            "[AUTH] 회원가입 화면 구현",
            IssueSummary.withDomainPrefix("[AUTH-001] 회원가입 화면 구현"),
        )
    }

    @Test
    fun `drops the area that used to follow the feature id`() {
        assertEquals(
            "[AUTH] 회원가입 화면 구현",
            IssueSummary.withDomainPrefix("[AUTH-001 | FE] 회원가입 화면 구현"),
        )
    }

    @Test
    fun `drops the area that used to follow a work category`() {
        assertEquals(
            "[INFRA] CI/CD 파이프라인 구축",
            IssueSummary.withDomainPrefix("[INFRA | BE] CI/CD 파이프라인 구축"),
        )
    }

    @Test
    fun `leaves a title that already names one code alone`() {
        assertEquals("[PLAN] 서비스 목표 정의", IssueSummary.withDomainPrefix("[PLAN] 서비스 목표 정의"))
        assertEquals("[AUTH] 회원가입 화면 구현", IssueSummary.withDomainPrefix("[AUTH] 회원가입 화면 구현"))
    }

    @Test
    fun `copes with the separators written without spaces`() {
        assertEquals("[AUTH] 로그인 연동", IssueSummary.withDomainPrefix("[AUTH-001|FE] 로그인 연동"))
        assertEquals("[INFRA] 배포", IssueSummary.withDomainPrefix("[INFRA   |   BE] 배포"))
    }

    @Test
    fun `keeps one space between the code and the name`() {
        assertEquals("[AUTH] 회원가입", IssueSummary.withDomainPrefix("   [AUTH-001 | FE]    회원가입   "))
    }

    @Test
    fun `has a title to offer when the bracket is all there is`() {
        assertEquals("[AUTH]", IssueSummary.withDomainPrefix("[AUTH-001 | FE]"))
        assertEquals("[INFRA]", IssueSummary.withDomainPrefix("[INFRA | BE]"))
    }

    @Test
    fun `leaves a title with no leading bracket alone`() {
        assertEquals("회원가입 화면 구현", IssueSummary.withDomainPrefix("회원가입 화면 구현"))
        // 대괄호가 앞이 아니면 도메인 표기가 아니다.
        assertEquals("회원가입 [AUTH-001] 구현", IssueSummary.withDomainPrefix("회원가입 [AUTH-001] 구현"))
    }

    @Test
    fun `leaves a bracket that names no code alone`() {
        // `[]`로 바꿔 놓지 않는다 — 사람이 적어 둔 말을 지우는 쪽이 더 나쁘다.
        assertEquals("[] 회원가입", IssueSummary.withDomainPrefix("[] 회원가입"))
        assertEquals("[ | FE] 회원가입", IssueSummary.withDomainPrefix("[ | FE] 회원가입"))
        assertEquals("[-001] 회원가입", IssueSummary.withDomainPrefix("[-001] 회원가입"))
    }

    @Test
    fun `leaves a bracket that never closes alone`() {
        assertEquals("[AUTH-001 회원가입", IssueSummary.withDomainPrefix("[AUTH-001 회원가입"))
    }

    @Test
    fun `keeps only what comes before the first separator`() {
        assertEquals("[AUTH] 회원가입", IssueSummary.withDomainPrefix("[AUTH-001-2] 회원가입"))
        assertEquals("[AUTH] 회원가입", IssueSummary.withDomainPrefix("[AUTH-001 | FE | BE] 회원가입"))
    }

    @Test
    fun `has nothing to change about an empty title`() {
        assertEquals("", IssueSummary.withDomainPrefix(""))
        assertEquals("", IssueSummary.withDomainPrefix("   "))
    }
}
