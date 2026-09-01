package io.github.gudwns1812.jiratask.ui

import io.github.gudwns1812.jiratask.domain.mr.GitLabAuthenticationError
import io.github.gudwns1812.jiratask.domain.mr.GitLabError
import io.github.gudwns1812.jiratask.domain.mr.GitLabIdentity
import io.github.gudwns1812.jiratask.domain.mr.GitLabNetworkError
import io.github.gudwns1812.jiratask.domain.mr.GitLabPermissionError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 설정 화면의 "연결 테스트"가 무엇을 말하는가. 원인마다 고칠 곳이 다르므로 뭉뚱그리지 않는다. */
class GitLabConnectionCheckTest {

    @Test
    fun `asks for what is missing before calling gitlab`() {
        assertEquals(GitLabConnectionCheck.NO_BASE_URL, GitLabConnectionCheck.inputProblem("", hasToken = true))
        assertEquals(GitLabConnectionCheck.NO_BASE_URL, GitLabConnectionCheck.inputProblem("   ", hasToken = true))
        assertEquals(
            GitLabConnectionCheck.NOT_HTTP,
            GitLabConnectionCheck.inputProblem("lab.ssafy.com", hasToken = true),
        )
        assertEquals(
            GitLabConnectionCheck.NO_TOKEN,
            GitLabConnectionCheck.inputProblem("https://lab.ssafy.com", hasToken = false),
        )
    }

    @Test
    fun `has nothing to complain about when both are given`() {
        assertNull(GitLabConnectionCheck.inputProblem("https://lab.ssafy.com", hasToken = true))
        assertNull(GitLabConnectionCheck.inputProblem("http://gitlab.local", hasToken = true))
    }

    @Test
    fun `names the account the token belongs to`() {
        val report = GitLabConnectionCheck.of(
            GitLabIdentity("gudwns1812", scopesKnown = true, canCreateMergeRequest = true),
        )

        assertTrue(report.isSuccess)
        assertTrue(report.message.contains("gudwns1812"))
        assertFalse(report.message.contains(GitLabConnectionCheck.NEEDS_API_SCOPE))
    }

    @Test
    fun `warns that a read-only token cannot create a merge request`() {
        val report = GitLabConnectionCheck.of(
            GitLabIdentity("reader", scopesKnown = true, canCreateMergeRequest = false),
        )

        // 연결 자체는 됐으므로 실패로 보고하지 않는다. 다만 생성에서 막힐 것을 미리 알린다.
        assertTrue(report.isSuccess)
        assertTrue(report.message.contains("reader"))
        assertTrue(report.message.contains(GitLabConnectionCheck.NEEDS_API_SCOPE))
    }

    @Test
    fun `says nothing about scopes it could not read`() {
        val report = GitLabConnectionCheck.of(GitLabIdentity("gudwns1812", scopesKnown = false))

        assertTrue(report.isSuccess)
        assertFalse("모른다고 해서 경고하지 않는다", report.message.contains(GitLabConnectionCheck.NEEDS_API_SCOPE))
    }

    @Test
    fun `tells the user where to fix each kind of failure`() {
        val invalid = GitLabConnectionCheck.of(GitLabAuthenticationError())
        val forbidden = GitLabConnectionCheck.of(GitLabPermissionError())
        val offline = GitLabConnectionCheck.of(GitLabNetworkError())
        val rejected = GitLabConnectionCheck.of(GitLabError("GitLab 요청이 거부되었습니다."))
        val unknown = GitLabConnectionCheck.of(IllegalStateException("boom"))

        listOf(invalid, forbidden, offline, rejected, unknown).forEach { assertFalse(it.isSuccess) }
        assertTrue("토큰을 다시 발급하라고 말한다", invalid.message.contains("발급"))
        assertTrue("스코프와 역할을 짚는다", forbidden.message.contains("api"))
        assertTrue("주소와 네트워크를 짚는다", offline.message.contains("네트워크"))
        assertTrue(rejected.message.contains("거부"))
        assertTrue(unknown.message.contains(GitLabConnectionCheck.UNKNOWN))
    }

    @Test
    fun `never puts a token into what it shows`() {
        val secret = "glpat-SECRET-TOKEN-VALUE"
        val messages = listOf(
            GitLabConnectionCheck.of(GitLabIdentity("gudwns1812", scopesKnown = true)).message,
            GitLabConnectionCheck.of(GitLabAuthenticationError()).message,
            GitLabConnectionCheck.of(IllegalStateException(secret)).message,
        )

        messages.forEach { assertFalse("토큰은 화면에도 로그에도 남기지 않는다", it.contains(secret)) }
    }
}
