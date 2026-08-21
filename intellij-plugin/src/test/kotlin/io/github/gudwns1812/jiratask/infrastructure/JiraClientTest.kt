package io.github.gudwns1812.jiratask.infrastructure

import io.github.gudwns1812.jiratask.domain.jira.JiraAuthenticationError
import io.github.gudwns1812.jiratask.domain.jira.JiraNetworkError
import io.github.gudwns1812.jiratask.domain.jira.JiraRequestError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private data class RecordedCall(val url: String, val method: String, val body: String?, val headers: Map<String, String>)

private class ScriptedJiraHttpTransport(private val responses: MutableList<JiraHttpResponse>) : JiraHttpTransport {
    val calls = mutableListOf<RecordedCall>()

    override fun send(url: String, method: String, body: String?, headers: Map<String, String>): JiraHttpResponse {
        calls.add(RecordedCall(url, method, body, headers))
        check(responses.isNotEmpty()) { "예상보다 많은 요청이 발생했습니다: $url" }
        return responses.removeAt(0)
    }
}

private class ThrowingJiraHttpTransport : JiraHttpTransport {
    override fun send(url: String, method: String, body: String?, headers: Map<String, String>): JiraHttpResponse {
        throw java.io.IOException("offline")
    }
}

class JiraClientTest {

    @Test
    fun `reads an issue then transitions it using a dynamically found transition id`() {
        val transport = ScriptedJiraHttpTransport(
            mutableListOf(
                JiraHttpResponse(
                    200,
                    """{"key":"S15P11A108-100","fields":{"issuetype":{"id":"10","name":"Story"},"status":{"id":"1","name":"To Do"}}}""",
                ),
                JiraHttpResponse(200, """{"transitions":[{"id":"31","to":{"id":"2","name":"In Progress"}}]}"""),
                JiraHttpResponse(204, ""),
            ),
        )
        val client = JiraClient("https://team.atlassian.net", "user@example.com", "secret", transport)

        val issue = client.getIssue("S15P11A108-100")
        assertEquals("10", issue.issueTypeId)
        assertEquals("Story", issue.issueTypeName)
        assertEquals("1", issue.statusId)
        assertEquals("To Do", issue.statusName)

        assertEquals("31", client.findTransitionId("S15P11A108-100", "2"))
        client.transition("S15P11A108-100", "31")

        val lastCall = transport.calls.last()
        assertEquals("https://team.atlassian.net/rest/api/3/issue/S15P11A108-100/transitions", lastCall.url)
        assertEquals("POST", lastCall.method)
        assertEquals("""{"transition":{"id":"31"}}""", lastCall.body)
        assertFalse(transport.calls.any { it.headers.values.any { header -> header.contains("secret") } })
    }

    @Test
    fun `separates authentication errors from network errors and never leaks credentials`() {
        val authTransport = ScriptedJiraHttpTransport(mutableListOf(JiraHttpResponse(401, "")))
        val authClient = JiraClient("https://team.atlassian.net", "user@example.com", "secret", authTransport)
        assertThrows(JiraAuthenticationError::class.java) { authClient.getIssue("S15P11A108-100") }

        val networkClient = JiraClient("https://team.atlassian.net", "user@example.com", "secret", ThrowingJiraHttpTransport())
        assertThrows(JiraNetworkError::class.java) { networkClient.getIssue("S15P11A108-100") }
    }

    @Test
    fun `verifies identity, pages through projects, and reads issue types with statuses`() {
        val transport = ScriptedJiraHttpTransport(
            mutableListOf(
                JiraHttpResponse(200, """{"accountId":"user"}"""),
                JiraHttpResponse(200, """{"values":[{"id":"1","key":"ONE","name":"첫 프로젝트"}],"isLast":false,"total":2}"""),
                JiraHttpResponse(200, """{"values":[{"id":"2","key":"TWO","name":"둘째 프로젝트"}],"isLast":true,"total":2}"""),
                JiraHttpResponse(200, """[{"id":"10","name":"스토리","statuses":[{"id":"3","name":"진행 중"}]}]"""),
            ),
        )
        val client = JiraClient("https://team.atlassian.net", "user@example.com", "secret", transport)

        client.verifyIdentity()

        val projects = client.listProjects()
        assertEquals(2, projects.size)
        assertEquals("ONE", projects[0].key)
        assertEquals("TWO", projects[1].key)

        val issueTypes = client.listIssueTypesWithStatuses("ONE")
        assertEquals(1, issueTypes.size)
        assertEquals("스토리", issueTypes[0].name)
        assertEquals("진행 중", issueTypes[0].statuses.single().name)
    }

    @Test
    fun `ends with a request error when setup metadata responses are malformed`() {
        val transport = ScriptedJiraHttpTransport(mutableListOf(JiraHttpResponse(200, """{"values":[]}""")))
        val client = JiraClient("https://team.atlassian.net", "user@example.com", "secret", transport)

        assertThrows(JiraRequestError::class.java) { client.listProjects() }
    }

    @Test
    fun `does not loop forever when the next page is empty but not marked last`() {
        val responses = object {
            var count = 0
        }
        val transport = object : JiraHttpTransport {
            override fun send(url: String, method: String, body: String?, headers: Map<String, String>): JiraHttpResponse {
                responses.count++
                check(responses.count <= 5) { "무한 반복이 감지되었습니다." }
                return JiraHttpResponse(200, """{"values":[],"isLast":false}""")
            }
        }
        val client = JiraClient("https://team.atlassian.net", "user@example.com", "secret", transport)

        assertThrows(JiraRequestError::class.java) { client.listProjects() }
        assertTrue(responses.count <= 5)
    }

    @Test
    fun `returns null when no transition targets the requested status`() {
        val transport = ScriptedJiraHttpTransport(
            mutableListOf(JiraHttpResponse(200, """{"transitions":[{"id":"31","to":{"id":"2","name":"In Progress"}}]}""")),
        )
        val client = JiraClient("https://team.atlassian.net", "user@example.com", "secret", transport)

        assertNull(client.findTransitionId("S15P11A108-100", "99"))
    }
}
