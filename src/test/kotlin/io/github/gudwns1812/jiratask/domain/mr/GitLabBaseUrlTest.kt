package io.github.gudwns1812.jiratask.domain.mr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 토큰은 주소를 열쇠 삼아 보관한다. 저장할 때와 찾을 때가 같은 모양이어야
 * 넣어 둔 토큰을 다시 찾을 수 있다.
 */
class GitLabBaseUrlTest {

    @Test
    fun `treats the same address written differently as one`() {
        val expected = "https://lab.ssafy.com"

        assertEquals(expected, GitLabBaseUrl.normalize("https://lab.ssafy.com"))
        assertEquals(expected, GitLabBaseUrl.normalize("https://lab.ssafy.com/"))
        assertEquals(expected, GitLabBaseUrl.normalize("  https://lab.ssafy.com//  ".trim()))
        assertEquals(expected, GitLabBaseUrl.normalize("https://LAB.SSAFY.COM"))
        assertEquals(expected, GitLabBaseUrl.normalize("HTTPS://Lab.Ssafy.Com/"))
    }

    @Test
    fun `keeps the path as written`() {
        // 호스트는 대소문자를 가리지 않지만 경로는 가린다. 경로까지 내리면 주소가 망가진다.
        assertEquals("https://host/GitLab", GitLabBaseUrl.normalize("https://HOST/GitLab/"))
        assertEquals("https://host/a/B/c", GitLabBaseUrl.normalize("https://host/a/B/c"))
    }

    @Test
    fun `keeps a port and a user name`() {
        assertEquals("https://host:8443", GitLabBaseUrl.normalize("https://HOST:8443/"))
        assertEquals("http://gitlab.local", GitLabBaseUrl.normalize("HTTP://GitLab.local"))
    }

    @Test
    fun `does not pretend a different scheme is the same place`() {
        // http와 https는 다른 곳일 수 있다. 합치지 않는다.
        assertEquals("http://lab.ssafy.com", GitLabBaseUrl.normalize("http://lab.ssafy.com"))
    }

    @Test
    fun `has nothing to normalize for an empty address`() {
        assertEquals("", GitLabBaseUrl.normalize(null))
        assertEquals("", GitLabBaseUrl.normalize(""))
        assertEquals("", GitLabBaseUrl.normalize("   "))
        assertEquals("", GitLabBaseUrl.normalize("/"))
    }

    @Test
    fun `still returns something for an address it cannot parse`() {
        assertEquals("lab.ssafy.com", GitLabBaseUrl.normalize("LAB.SSAFY.COM"))
    }
}
