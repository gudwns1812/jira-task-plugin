package io.github.gudwns1812.jiratask.domain.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 이슈 키를 Jira로 잇는 규칙. 오탐을 내지 않는 것과 사용자 설정을 지우지 않는 것이 핵심이다. */
class IssueLinkRuleTest {

    private fun rule(key: String? = "S15P11A108", base: String? = "https://team.atlassian.net") =
        IssueLinkRule.of(key, base)

    private fun matches(text: String, rule: IssueLinkRule = rule()!!) =
        Regex(rule.issueRegexp).containsMatchIn(text)

    @Test
    fun `builds a pattern for the configured project key`() {
        val built = rule()!!

        assertEquals("""\bS15P11A108-\d+\b""", built.issueRegexp)
        assertEquals("https://team.atlassian.net/browse/\$0", built.linkRegexp)
    }

    @Test
    fun `drops a trailing slash from the jira address`() {
        assertEquals("https://team.atlassian.net/browse/\$0", rule(base = "https://team.atlassian.net/")!!.linkRegexp)
    }

    @Test
    fun `has no rule to offer without a project key or an address`() {
        assertNull(rule(key = null))
        assertNull(rule(key = "  "))
        assertNull(rule(base = null))
        assertNull(rule(base = ""))
    }

    @Test
    fun `matches the keys of the configured project`() {
        assertTrue(matches("S15P11A108-81 회원가입"))
        assertTrue(matches("fix for S15P11A108-1 here"))
        assertTrue(matches("(S15P11A108-999)"))
    }

    @Test
    fun `does not match another project's key`() {
        // 아무 대문자-숫자 조합에 반응하면 오탐이 심하다.
        assertFalse(matches("OTHER-81 회원가입"))
    }

    @Test
    fun `does not match things that merely look like a key`() {
        assertFalse(matches("UTF-8"))
        assertFalse(matches("HTTP-2"))
        assertFalse(matches("SHA-256"))
    }

    @Test
    fun `does not match a key glued to other letters`() {
        assertFalse(matches("XS15P11A108-81"))
        assertFalse("번호 없는 키는 링크가 아니다", matches("S15P11A108-"))
    }

    @Test
    fun `treats a key with regex characters as plain text`() {
        val built = IssueLinkRule.of("A.B", "https://x")!!

        assertTrue(Regex(built.issueRegexp).containsMatchIn("A.B-1"))
        assertFalse("`.`를 아무 글자로 읽으면 안 된다", Regex(built.issueRegexp).containsMatchIn("AxB-1"))
    }

    private fun ours() = rule()!!

    private fun theirs() = IssueLinkRule("""[A-Z]+-\d+""", "https://old.example.com/issue/\$0")

    @Test
    fun `recognises the rule it made itself`() {
        assertTrue(IssueLinkRule.isOurs(ours().issueRegexp, ours().linkRegexp))
    }

    @Test
    fun `does not claim a rule the user wrote by hand`() {
        assertFalse(IssueLinkRule.isOurs(theirs().issueRegexp, theirs().linkRegexp))
        assertFalse(IssueLinkRule.isOurs(null, null))
    }

    @Test
    fun `replaces its own stale rule and keeps the user's`() {
        val stale = IssueLinkRule("""\bOLD-\d+\b""", "https://old.atlassian.net/browse/\$0")

        val result = ours().applyTo(listOf(theirs(), stale))

        assertEquals(listOf(theirs(), ours()), result)
    }

    @Test
    fun `adds itself to a list that has nothing yet`() {
        assertEquals(listOf(ours()), ours().applyTo(emptyList()))
    }

    @Test
    fun `does not pile up duplicates when applied twice`() {
        val once = ours().applyTo(emptyList())

        assertEquals(once, ours().applyTo(once))
    }

    @Test
    fun `removes only its own rule when the settings are gone`() {
        val result = IssueLinkRule.removeOursFrom(listOf(theirs(), ours()))

        assertEquals(listOf(theirs()), result)
    }
}
