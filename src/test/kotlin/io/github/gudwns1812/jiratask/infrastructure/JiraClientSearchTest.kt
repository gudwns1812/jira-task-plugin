package io.github.gudwns1812.jiratask.infrastructure

import io.github.gudwns1812.jiratask.domain.jira.JiraAuthenticationError
import io.github.gudwns1812.jiratask.domain.jira.JiraNetworkError
import io.github.gudwns1812.jiratask.domain.jira.JiraRequestError
import io.github.gudwns1812.jiratask.domain.jira.JiraStatusCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private data class SearchCall(val url: String, val method: String, val body: String?)

private class SearchTransport(private val responses: MutableList<JiraHttpResponse>) : JiraHttpTransport {
    val calls = mutableListOf<SearchCall>()

    override fun send(url: String, method: String, body: String?, headers: Map<String, String>): JiraHttpResponse {
        calls.add(SearchCall(url, method, body))
        check(responses.isNotEmpty()) { "예상보다 많은 요청이 발생했습니다: $url" }
        return responses.removeAt(0)
    }
}

private class OfflineTransport : JiraHttpTransport {
    override fun send(url: String, method: String, body: String?, headers: Map<String, String>): JiraHttpResponse =
        throw java.io.IOException("offline")
}

private fun issue(key: String, summary: String, statusName: String, categoryKey: String): String =
    """{"key":"$key","fields":{"summary":"$summary","status":{"name":"$statusName",""" +
        """"statusCategory":{"key":"$categoryKey"}}}}"""

private fun page(vararg issues: String, nextPageToken: String? = null): String {
    val token = nextPageToken?.let { ""","nextPageToken":"$it"""" }.orEmpty()
    return """{"issues":[${issues.joinToString(",")}]$token}"""
}

/** 스프린트 작업 검색. 무엇을 거르고 무엇을 거르지 않는지가 핵심이다(RULE.md 6.1). */
class JiraClientSearchTest {

    private fun clientOf(vararg bodies: String): Pair<JiraClient, SearchTransport> {
        val transport = SearchTransport(bodies.map { JiraHttpResponse(200, it) }.toMutableList())
        return JiraClient("https://team.atlassian.net", "user@example.com", "secret", transport) to transport
    }

    @Test
    fun `reads my tasks in the open sprint with their status category`() {
        val (client, transport) = clientOf(
            page(
                issue("S15P11A108-257", "[AUTH-001 | FE] 회원가입 화면 구현", "해야 할 일", "new"),
                issue("S15P11A108-258", "[AUTH-001 | BE] 회원가입 API 구현", "진행 중", "indeterminate"),
            ),
        )

        val tasks = client.currentSprintTasks("S15P11A108", "10002")

        assertEquals(listOf("S15P11A108-257", "S15P11A108-258"), tasks.map { it.key })
        assertEquals(JiraStatusCategory.TO_DO, tasks[0].statusCategory)
        assertEquals(JiraStatusCategory.IN_PROGRESS, tasks[1].statusCategory)
        assertTrue("목록으로 받은 작업은 정의상 열린 스프린트 안에 있다", tasks.all { it.inOpenSprint })
        assertEquals("S15P11A108-257  [AUTH-001 | FE] 회원가입 화면 구현", tasks[0].label())
    }

    @Test
    fun `asks only for my tasks of the task type in an open sprint`() {
        val (client, transport) = clientOf(page())

        client.currentSprintTasks("S15P11A108", "10002")

        val url = transport.calls.single().url
        assertTrue(url.contains("/rest/api/3/search/jql"))
        listOf("issuetype%20%3D%2010002", "assignee%20%3D%20currentUser%28%29", "sprint%20IN%20openSprints%28%29").forEach {
            assertTrue("$it 조건이 있어야 한다: $url", url.contains(it))
        }
    }

    @Test
    fun `keeps the finished tasks instead of filtering them out`() {
        // 끝난 작업에도 후속 커밋이 붙는다. 거르는 일은 화면이 한다(RULE.md 6.1).
        val (client, transport) = clientOf(
            page(issue("S15P11A108-1", "끝난 작업", "완료", "done")),
        )

        val tasks = client.currentSprintTasks("S15P11A108", "10002")

        assertEquals(1, tasks.size)
        assertTrue(tasks.single().statusCategory.isDone)
        assertFalse("JQL에 상태 조건을 넣지 않는다", transport.calls.single().url.contains("statusCategory"))
    }

    @Test
    fun `reads a done category even when the status is not named 완료`() {
        val (client, _) = clientOf(page(issue("S15P11A108-1", "취소된 작업", "Cancelled", "done")))

        assertTrue(client.currentSprintTasks("S15P11A108", "10002").single().statusCategory.isDone)
    }

    @Test
    fun `does not treat an unknown status category as finished`() {
        val (client, _) = clientOf(
            page("""{"key":"S15P11A108-1","fields":{"summary":"상태 없음","status":null}}"""),
        )

        val task = client.currentSprintTasks("S15P11A108", "10002").single()

        assertEquals(JiraStatusCategory.UNKNOWN, task.statusCategory)
        assertFalse(task.statusCategory.isDone)
    }

    @Test
    fun `returns nothing when no sprint is open or nothing is assigned to me`() {
        val (client, _) = clientOf(page())

        assertTrue(client.currentSprintTasks("S15P11A108", "10002").isEmpty())
    }

    @Test
    fun `follows the next page token until the last page`() {
        val (client, transport) = clientOf(
            page(issue("S15P11A108-1", "첫 장", "해야 할 일", "new"), nextPageToken = "tok en/1"),
            page(issue("S15P11A108-2", "둘째 장", "해야 할 일", "new")),
        )

        val tasks = client.currentSprintTasks("S15P11A108", "10002")

        assertEquals(listOf("S15P11A108-1", "S15P11A108-2"), tasks.map { it.key })
        assertTrue("토큰은 인코딩해서 보낸다", transport.calls[1].url.contains("nextPageToken=tok%20en%2F1"))
    }

    @Test
    fun `stops when a page is empty but still offers a token`() {
        // 빈 페이지에 토큰만 계속 오면 무한 루프가 된다. 한 번 비면 멈춘다.
        val (client, transport) = clientOf(page(nextPageToken = "endless"))

        assertTrue(client.currentSprintTasks("S15P11A108", "10002").isEmpty())
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun `ends with a request error when the search response has no issues array`() {
        val (client, _) = clientOf("""{"unexpected":true}""")

        assertThrows(JiraRequestError::class.java) { client.currentSprintTasks("S15P11A108", "10002") }
    }

    @Test
    fun `separates a permission failure from a network failure`() {
        val forbidden = SearchTransport(mutableListOf(JiraHttpResponse(403, "{}")))
        val client = JiraClient("https://team.atlassian.net", "user@example.com", "secret", forbidden)

        assertThrows(JiraAuthenticationError::class.java) { client.currentSprintTasks("S15P11A108", "10002") }
        assertThrows(JiraNetworkError::class.java) {
            JiraClient("https://team.atlassian.net", "user@example.com", "secret", OfflineTransport())
                .currentSprintTasks("S15P11A108", "10002")
        }
    }

    @Test
    fun `reads one task and asks separately whether it sits in an open sprint`() {
        val (client, transport) = clientOf(
            """{"key":"S15P11A108-257","fields":{"summary":"회원가입","status":{"name":"완료","statusCategory":{"key":"done"}}}}""",
            page(issue("S15P11A108-257", "회원가입", "완료", "done")),
        )

        val task = client.task("S15P11A108-257")!!

        assertEquals("S15P11A108-257", task.key)
        assertTrue("끝난 작업도 그대로 돌려준다", task.statusCategory.isDone)
        assertTrue(task.inOpenSprint)
        assertTrue(transport.calls[1].url.contains("openSprints%28%29"))
    }

    @Test
    fun `reports a task outside the open sprint`() {
        val (client, _) = clientOf(
            """{"key":"S15P11A108-9","fields":{"summary":"지난 스프린트","status":{"name":"완료","statusCategory":{"key":"done"}}}}""",
            page(),
        )

        assertFalse(client.task("S15P11A108-9")!!.inOpenSprint)
    }

    @Test
    fun `assumes the sprint is fine when the sprint question itself fails`() {
        // 확인할 수 없는 것과 위반한 것은 다르다. 가드가 거짓 경고를 내지 않아야 한다(RULE.md 5.2).
        val (client, _) = clientOf(
            """{"key":"S15P11A108-9","fields":{"summary":"스프린트 필드 없음","status":{"name":"진행 중","statusCategory":{"key":"indeterminate"}}}}""",
            """{"broken":true}""",
        )

        assertTrue(client.task("S15P11A108-9")!!.inOpenSprint)
    }

    @Test
    fun `has no task to offer for a key that does not exist`() {
        val transport = SearchTransport(mutableListOf(JiraHttpResponse(404, "{}")))
        val client = JiraClient("https://team.atlassian.net", "user@example.com", "secret", transport)

        assertNull(client.task("S15P11A108-9999"))
    }

    @Test
    fun `searches by summary without narrowing to me, my sprint or my status`() {
        val (client, transport) = clientOf(page(issue("S15P11A108-1", "회원가입 화면", "완료", "done")))

        val found = client.searchByText("S15P11A108", "회원가입", limit = 20)

        assertEquals(listOf("S15P11A108-1"), found.map { it.key })
        val url = transport.calls.single().url
        assertFalse("담당자로 좁히지 않는다", url.contains("currentUser"))
        assertFalse("스프린트로 좁히지 않는다", url.contains("openSprints"))
        assertFalse("상태로 좁히지 않는다", url.contains("statusCategory"))
    }

    @Test
    fun `keeps a quote in the search text from breaking the jql`() {
        val (client, transport) = clientOf(page())

        client.searchByText("S15P11A108", """he said "hi" \ there""", limit = 20)

        // 따옴표와 역슬래시가 이스케이프된 채로 인코딩돼야 JQL 문자열이 깨지지 않는다.
        assertTrue(transport.calls.single().url.contains("%5C%22hi%5C%22"))
    }

    @Test
    fun `never asks for more than the requested number of results`() {
        val (client, transport) = clientOf(page(issue("S15P11A108-1", "하나", "해야 할 일", "new")))

        client.searchByText("S15P11A108", "회원", limit = 3)

        assertTrue(transport.calls.single().url.contains("maxResults=3"))
    }

    @Test
    fun `opens an issue with its description flattened back to editable text`() {
        val adf = """{"type":"doc","version":1,"content":[""" +
            """{"type":"heading","attrs":{"level":3},"content":[{"type":"text","text":"설명"}]},""" +
            """{"type":"paragraph","content":[{"type":"text","text":"화면을 만든다."}]},""" +
            """{"type":"taskList","content":[{"type":"taskItem","content":[{"type":"text","text":"입력 검증"}]}]}]}"""
        val (client, _) = clientOf(
            """{"key":"S15P11A108-81","fields":{"summary":"회원가입","description":$adf,""" +
                """"status":{"name":"진행 중"},"assignee":{"displayName":"박형준"}}}""",
            """{"fields":{"sprint":{"name":"Sprint 1"}}}""",
        )

        val detail = client.detail("S15P11A108-81")!!

        assertEquals("회원가입", detail.summary)
        assertEquals(listOf("## 설명", "화면을 만든다.", "[] 입력 검증"), detail.description.lines())
        assertEquals("진행 중", detail.statusName)
        assertEquals("박형준", detail.assignee)
        assertEquals("Sprint 1", detail.sprint)
    }

    @Test
    fun `opens an issue whose description is empty or missing`() {
        val (client, _) = clientOf(
            """{"key":"S15P11A108-81","fields":{"summary":"제목만","status":{"name":"할 일"}}}""",
            """{"fields":{}}""",
        )

        val detail = client.detail("S15P11A108-81")!!

        assertEquals("", detail.description)
        assertEquals("", detail.assignee)
        assertEquals("", detail.sprint)
    }

    @Test
    fun `still opens the issue when the sprint name cannot be read`() {
        // 보여 주기용 값 하나 때문에 상세 조회를 실패시키지 않는다.
        val (client, _) = clientOf(
            """{"key":"S15P11A108-81","fields":{"summary":"제목","status":{"name":"할 일"}}}""",
            """{"broken":true}""",
        )

        assertEquals("", client.detail("S15P11A108-81")!!.sprint)
    }

    @Test
    fun `has no detail to offer for a key that does not exist`() {
        val transport = SearchTransport(mutableListOf(JiraHttpResponse(404, "{}")))
        val client = JiraClient("https://team.atlassian.net", "user@example.com", "secret", transport)

        assertNull(client.detail("S15P11A108-9999"))
    }

    @Test
    fun `sends only the summary and the description when saving`() {
        // 상태와 스프린트는 automation과 계획의 영역이다(RULE.md 6.2).
        val transport = SearchTransport(mutableListOf(JiraHttpResponse(204, "")))
        val client = JiraClient("https://team.atlassian.net", "user@example.com", "secret", transport)

        client.updateSummaryAndDescription("S15P11A108-81", "새 제목", listOf("## 설명", "새 설명").joinToString("\n"))

        val call = transport.calls.single()
        assertTrue(call.url.endsWith("/rest/api/3/issue/S15P11A108-81"))
        assertEquals("PUT", call.method)
        val body = call.body.orEmpty()
        assertTrue(body.contains(""""summary":"새 제목""""))
        assertTrue("설명은 ADF로 보낸다", body.contains(""""type":"doc""""))
        listOf("status", "sprint", "assignee").forEach {
            assertFalse("$it 은(는) 보내지 않는다: $body", body.contains(""""$it""""))
        }
    }

    @Test
    fun `reports a conflict instead of pretending the save worked`() {
        val transport = SearchTransport(mutableListOf(JiraHttpResponse(409, "{}")))
        val client = JiraClient("https://team.atlassian.net", "user@example.com", "secret", transport)

        assertThrows(JiraRequestError::class.java) {
            client.updateSummaryAndDescription("S15P11A108-81", "새 제목", "설명")
        }
    }
}
