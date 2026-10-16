package io.github.gudwns1812.jiratask.infrastructure

import io.github.gudwns1812.jiratask.domain.mr.GitLabAuthenticationError
import io.github.gudwns1812.jiratask.domain.mr.GitLabError
import io.github.gudwns1812.jiratask.domain.mr.GitLabNetworkError
import io.github.gudwns1812.jiratask.domain.mr.GitLabPermissionError
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
    fun `verifies the token and reports who it belongs to`() {
        val (client, transport) = clientOf(
            ok("""{"username":"gudwns1812","name":"박형준"}"""),
            ok("""{"scopes":["api","read_user"]}"""),
        )

        val identity = client.verifyIdentity()

        assertEquals("gudwns1812", identity.username)
        assertTrue(identity.canCreateMergeRequest)
        assertTrue(!identity.lacksApiScope)
        assertTrue(transport.calls.first().url.endsWith("/api/v4/user"))
    }

    @Test
    fun `reads who the token belongs to, with the id the api needs`() {
        val (client, transport) = clientOf(ok("""{"id":42,"username":"gudwns1812","name":"박형준"}"""))

        val me = client.me()

        assertEquals(42, me?.id)
        assertEquals("박형준 (gudwns1812)", me?.label())
        assertTrue(transport.calls.single().url.endsWith("/api/v4/user"))
    }

    @Test
    fun `reads the labels the project already has`() {
        val (client, transport) = clientOf(
            ok("""[{"name":"backend"},{"name":"review-needed"},{"name":"backend"}]"""),
        )

        assertEquals(listOf("backend", "review-needed"), client.labels("s15/A403"))
        assertTrue(transport.calls.single().url.contains("/projects/s15%2FA403/labels"))
    }

    @Test
    fun `asks gitlab to narrow the people down when a name is given`() {
        // 사람이 수백 명인 프로젝트에서 전체를 끌어오지 않는다. 검색어를 그대로 넘긴다.
        val (client, transport) = clientOf(ok("""[{"id":7,"username":"jimin","name":"김지민"}]"""))

        val found = client.findUsers("s15/A403", "지민")

        assertEquals(listOf("김지민 (jimin)"), found.map { it.label() })
        assertTrue(transport.calls.single().url.contains("search="))
    }

    @Test
    fun `searches the whole gitlab when no project is known yet`() {
        // 설정 화면은 아직 어느 저장소인지 모른다. 이름만으로 사람을 찾을 수 있어야 한다.
        val (client, transport) = clientOf(ok("""[{"id":7,"username":"jimin","name":"김지민"}]"""))

        val found = client.searchUsers("지민")

        assertEquals(listOf("김지민 (jimin)"), found.map { it.label() })
        assertTrue(transport.calls.single().url.contains("/api/v4/users?"))
    }

    @Test
    fun `does not ask gitlab for everyone when the query is empty`() {
        val (client, transport) = clientOf()

        assertEquals(emptyList<Any>(), client.searchUsers("   "))
        assertTrue("빈 검색어로 인스턴스 전체를 끌어오지 않는다", transport.calls.isEmpty())
    }

    @Test
    fun `has nobody and no labels to offer when gitlab refuses`() {
        // 사람·라벨을 못 읽는 것과 MR을 못 만드는 것은 다르다. 여기서 실패를 던지지 않는다.
        val (client, _) = clientOf(JiraHttpResponse(403, """{"message":"403 Forbidden"}"""))
        assertEquals(emptyList<String>(), client.labels("s15/A403"))

        val (other, _) = clientOf(JiraHttpResponse(500, "nope"))
        assertNull(other.me())
    }

    @Test
    fun `sends assignees, reviewers and labels along with the merge request`() {
        val (client, transport) = clientOf(ok("""{"web_url":"https://lab.ssafy.com/a/b/-/merge_requests/7"}"""))

        client.create(
            MergeRequestCommand(
                projectPath = "s15/A403",
                sourceBranch = "feat/signup",
                targetBranch = "backend-dev",
                title = "[AUTH] 회원가입",
                description = "### 연결된 작업",
                assigneeIds = listOf(42),
                reviewerIds = listOf(7, 9),
                labels = listOf("backend", "review-needed"),
            ),
        )

        val body = transport.calls.single().body.orEmpty()
        assertTrue(body.contains(""""assignee_ids":[42]"""))
        assertTrue(body.contains(""""reviewer_ids":[7,9]"""))
        assertTrue("라벨은 쉼표로 이어 붙여 보낸다", body.contains(""""labels":"backend,review-needed""""))
    }

    @Test
    fun `leaves out the people and labels it was not given`() {
        // 빈 배열을 보내면 GitLab이 "전부 지우라"로 읽는다. 아예 보내지 않는다.
        val (client, transport) = clientOf(ok("""{"web_url":"https://lab.ssafy.com/a/b/-/merge_requests/8"}"""))

        client.create(
            MergeRequestCommand("s15/A403", "feat/x", "main", "제목", "본문"),
        )

        val body = transport.calls.single().body.orEmpty()
        assertTrue(!body.contains("assignee_ids"))
        assertTrue(!body.contains("reviewer_ids"))
        assertTrue(!body.contains("labels"))
    }

    @Test
    fun `warns about a token that can read but cannot create`() {
        // `read_api` 토큰은 조회를 전부 통과하고 MR 생성에서만 403으로 죽는다. 미리 잡아야 한다.
        val (client, _) = clientOf(ok("""{"username":"reader"}"""), ok("""{"scopes":["read_api"]}"""))

        val identity = client.verifyIdentity()

        assertTrue(identity.lacksApiScope)
        assertTrue(!identity.canCreateMergeRequest)
    }

    @Test
    fun `does not claim a scope problem when the scopes cannot be read`() {
        // 구버전 GitLab에는 이 엔드포인트가 없다. 모르는 것을 위반으로 바꾸지 않는다.
        val (client, _) = clientOf(ok("""{"username":"gudwns1812"}"""), JiraHttpResponse(404, "{}"))

        val identity = client.verifyIdentity()

        assertEquals("gudwns1812", identity.username)
        assertTrue("모른다고 해서 경고하지 않는다", !identity.lacksApiScope)
        assertTrue(!identity.scopesKnown)
    }

    @Test
    fun `refuses to verify an invalid token`() {
        val (invalid, _) = clientOf(JiraHttpResponse(401, "{}"))
        val (forbidden, _) = clientOf(JiraHttpResponse(403, "{}"))

        assertThrows(GitLabAuthenticationError::class.java) { invalid.verifyIdentity() }
        assertThrows(GitLabPermissionError::class.java) { forbidden.verifyIdentity() }
    }

    @Test
    fun `falls back to a readable name when gitlab sends no username`() {
        val (client, _) = clientOf(ok("""{"id":7}"""), JiraHttpResponse(404, "{}"))

        assertTrue(client.verifyIdentity().username.isNotBlank())
    }

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
    fun `reads the default branch and the project's own template in one call`() {
        // 템플릿을 위해 요청을 늘리지 않는다. 같은 응답에 둘 다 들어 있다.
        val (client, transport) = clientOf(
            ok("""{"default_branch":"develop","merge_requests_template":"## 관련 이슈"}"""),
        )

        val info = client.projectInfo("a/b")

        assertEquals("develop", info.defaultBranch)
        assertEquals("## 관련 이슈", info.mergeRequestTemplate)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun `has nothing to offer when the project cannot be read`() {
        // 대상 브랜치도 템플릿도 사용자가 고칠 수 있다. 여기서 실패시키지 않는다.
        val (client, _) = clientOf(JiraHttpResponse(404, "{}"))

        val info = client.projectInfo("a/b")

        assertNull(info.defaultBranch)
        assertNull(info.mergeRequestTemplate)
    }

    @Test
    fun `has no template when the project does not set one`() {
        val (client, _) = clientOf(ok("""{"default_branch":"main"}"""))

        assertNull(client.projectInfo("a/b").mergeRequestTemplate)
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
    fun `separates an invalid token from a token that lacks permission`() {
        // 401은 토큰을 다시 발급해야 하고, 403은 스코프나 역할을 손봐야 한다. 고칠 곳이 다르다.
        // `read_api`만 있는 토큰은 조회를 통과하고 MR 생성에서만 403으로 죽는다.
        val (invalid, _) = clientOf(JiraHttpResponse(401, "{}"))
        val (forbidden, _) = clientOf(JiraHttpResponse(403, "{}"))
        val command = MergeRequestCommand("a/b", "s", "t", "제목", "")

        assertThrows(GitLabAuthenticationError::class.java) { invalid.create(command) }
        assertThrows(GitLabPermissionError::class.java) { forbidden.create(command) }
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
    fun `still reports success when the create response has no url`() {
        val (client, _) = clientOf(ok("""{"id":7}"""))

        // 여기까지 왔으면 MR은 이미 GitLab에 만들어졌다. 실패로 돌리면 사용자가 또 만들려 든다.
        assertNull(client.create(MergeRequestCommand("a/b", "s", "t", "제목", "")).webUrl)
    }
}
