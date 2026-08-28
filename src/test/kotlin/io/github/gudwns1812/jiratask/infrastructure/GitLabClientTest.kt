package io.github.gudwns1812.jiratask.infrastructure

import io.github.gudwns1812.jiratask.domain.mr.GitLabAuthenticationError
import io.github.gudwns1812.jiratask.domain.mr.GitLabError
import io.github.gudwns1812.jiratask.domain.mr.GitLabNetworkError
import io.github.gudwns1812.jiratask.domain.mr.MergeRequestCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private data class GitLabCall(val url: String, val method: String, val body: String?, val headers: Map<String, String>)

private class GitLabTransport(private val responses: MutableList<JiraHttpResponse>) : JiraHttpTransport {
    val calls = mutableListOf<GitLabCall>()

    override fun send(url: String, method: String, body: String?, headers: Map<String, String>): JiraHttpResponse {
        calls.add(GitLabCall(url, method, body, headers))
        check(responses.isNotEmpty()) { "예상보다 많은 요청이 발생했습니다: $url" }
        return responses.removeAt(0)
    }
}

class GitLabClientTest {

    private fun clientOf(vararg responses: JiraHttpResponse): Pair<GitLabClient, GitLabTransport> {
        val transport = GitLabTransport(responses.toMutableList())
        return GitLabClient("https://lab.ssafy.com/", "token", transport) to transport
    }

    private fun ok(body: String) = JiraHttpResponse(200, body)

    @Test
    fun `creates a merge request and returns where it lives`() {
        val (client, transport) = clientOf(
            JiraHttpResponse(201, """{"web_url":"https://lab.ssafy.com/s15/A108/-/merge_requests/7"}"""),
        )

        val created = client.create(
            MergeRequestCommand("s15/A108", "feat/signup", "develop", "제목", "본문"),
        )

        assertEquals("https://lab.ssafy.com/s15/A108/-/merge_requests/7", created.webUrl)
        val call = transport.calls.single()
        assertEquals("POST", call.method)
        assertTrue("프로젝트 경로는 인코딩한다", call.url.contains("/projects/s15%2FA108/merge_requests"))
        assertTrue(call.body!!.contains(""""source_branch":"feat/signup""""))
        assertTrue(call.body.contains(""""target_branch":"develop""""))
    }

    @Test
    fun `sends the token in the header gitlab expects`() {
        val (client, transport) = clientOf(ok("""{"web_url":"https://x"}"""))

        client.create(MergeRequestCommand("a/b", "s", "t", "제목", ""))

        assertEquals("token", transport.calls.single().headers["PRIVATE-TOKEN"])
    }

    @Test
    fun `finds the merge request already open on this branch`() {
        val (client, transport) = clientOf(
            ok("""[{"web_url":"https://lab.ssafy.com/a/b/-/merge_requests/3","title":"이미 있음"}]"""),
        )

        val open = client.openMergeRequest("a/b", "feat/signup")

        assertEquals("https://lab.ssafy.com/a/b/-/merge_requests/3", open?.webUrl)
        assertEquals("이미 있음", open?.title)
        assertTrue(transport.calls.single().url.contains("state=opened"))
    }

    @Test
    fun `has no open merge request to report when the list is empty`() {
        val (client, _) = clientOf(ok("[]"))

        assertNull(client.openMergeRequest("a/b", "feat/signup"))
    }

    @Test
    fun `reads the default branch of the project`() {
        val (client, _) = clientOf(ok("""{"default_branch":"develop"}"""))

        assertEquals("develop", client.defaultBranch("a/b"))
    }

    @Test
    fun `has no default branch to offer when the project cannot be read`() {
        // 대상 브랜치는 사용자가 고칠 수 있다. 여기서 실패시키지 않는다.
        val (client, _) = clientOf(JiraHttpResponse(404, "{}"))

        assertNull(client.defaultBranch("a/b"))
    }

    @Test
    fun `separates a permission failure from a network failure`() {
        val (denied, _) = clientOf(JiraHttpResponse(401, "{}"))
        val offline = GitLabClient("https://lab.ssafy.com", "token") { _, _, _, _ ->
            throw java.io.IOException("offline")
        }

        assertThrows(GitLabAuthenticationError::class.java) {
            denied.create(MergeRequestCommand("a/b", "s", "t", "제목", ""))
        }
        assertThrows(GitLabNetworkError::class.java) {
            offline.create(MergeRequestCommand("a/b", "s", "t", "제목", ""))
        }
    }

    @Test
    fun `explains a missing project instead of a bare 404`() {
        val (client, _) = clientOf(JiraHttpResponse(404, "{}"))

        val error = assertThrows(GitLabError::class.java) {
            client.create(MergeRequestCommand("a/b", "s", "t", "제목", ""))
        }

        assertTrue(error.message!!.contains("권한"))
    }

    @Test
    fun `passes gitlab's own words through when it rejects the request`() {
        val (client, _) = clientOf(
            JiraHttpResponse(409, """{"message":["Another open merge request already exists"]}"""),
        )

        val error = assertThrows(GitLabError::class.java) {
            client.create(MergeRequestCommand("a/b", "s", "t", "제목", ""))
        }

        assertTrue(error.message!!.contains("Another open merge request already exists"))
    }

    @Test
    fun `reads a rejection whose message is an object of field errors`() {
        val (client, _) = clientOf(
            JiraHttpResponse(400, """{"message":{"source_branch":["can't be blank"]}}"""),
        )

        val error = assertThrows(GitLabError::class.java) {
            client.create(MergeRequestCommand("a/b", "s", "t", "제목", ""))
        }

        assertTrue(error.message!!.contains("source_branch"))
    }

    @Test
    fun `still says something when the rejection body is not json`() {
        val (client, _) = clientOf(JiraHttpResponse(400, "<html>nope</html>"))

        assertTrue(
            assertThrows(GitLabError::class.java) {
                client.create(MergeRequestCommand("a/b", "s", "t", "제목", ""))
            }.message!!.isNotBlank(),
        )
    }

    @Test
    fun `ends with an error when the create response has no url`() {
        val (client, _) = clientOf(ok("""{"id":7}"""))

        assertThrows(GitLabError::class.java) {
            client.create(MergeRequestCommand("a/b", "s", "t", "제목", ""))
        }
    }
}
