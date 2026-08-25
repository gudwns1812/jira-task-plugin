package io.github.gudwns1812.jiratask.infrastructure

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.gudwns1812.jiratask.domain.jira.JiraAuthenticationError
import io.github.gudwns1812.jiratask.domain.jira.JiraIssue
import io.github.gudwns1812.jiratask.domain.jira.JiraIssueTypeWithStatuses
import io.github.gudwns1812.jiratask.domain.jira.JiraNamedValue
import io.github.gudwns1812.jiratask.domain.jira.JiraNetworkError
import io.github.gudwns1812.jiratask.domain.jira.JiraProject
import io.github.gudwns1812.jiratask.domain.jira.JiraRequestError
import io.github.gudwns1812.jiratask.domain.jira.JiraSetupReader
import io.github.gudwns1812.jiratask.domain.jira.JiraStatusCategory
import io.github.gudwns1812.jiratask.domain.jira.JiraTransition
import io.github.gudwns1812.jiratask.domain.jira.SprintTask
import io.github.gudwns1812.jiratask.domain.jira.SprintTaskReader
import io.github.gudwns1812.jiratask.domain.jira.TaskIssueTransitioner
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

data class JiraHttpResponse(val statusCode: Int, val body: String)

/** 실제 네트워크 전송을 추상화해 테스트에서 가짜 응답을 주입할 수 있게 한다. */
fun interface JiraHttpTransport {
    fun send(url: String, method: String, body: String?, headers: Map<String, String>): JiraHttpResponse
}

class HttpClientJiraTransport(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) : JiraHttpTransport {
    override fun send(url: String, method: String, body: String?, headers: Map<String, String>): JiraHttpResponse {
        val builder = HttpRequest.newBuilder(URI(url))
        headers.forEach { (key, value) -> builder.header(key, value) }
        val bodyPublisher = if (body != null) HttpRequest.BodyPublishers.ofString(body) else HttpRequest.BodyPublishers.noBody()
        builder.method(method, bodyPublisher)

        val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return JiraHttpResponse(response.statusCode(), response.body())
    }
}

class JiraClient(
    baseUrl: String,
    email: String,
    apiToken: String,
    private val transport: JiraHttpTransport = HttpClientJiraTransport(),
) : JiraSetupReader, TaskIssueTransitioner, SprintTaskReader {

    private val baseUrl = baseUrl.trimEnd('/')
    private val authorization = "Basic " + Base64.getEncoder().encodeToString("$email:$apiToken".toByteArray())

    override fun getIssue(issueKey: String): JiraIssue {
        val body = requestObject("/rest/api/3/issue/${encode(issueKey)}?fields=issuetype,status,summary")
        val fields = body.objectOrNull("fields")
        val issuetype = fields?.objectOrNull("issuetype")
        val status = fields?.objectOrNull("status")

        val key = body.stringOrNull("key")
        val issueTypeId = issuetype?.stringOrNull("id")
        val issueTypeName = issuetype?.stringOrNull("name")
        val statusId = status?.stringOrNull("id")
        val statusName = status?.stringOrNull("name")

        if (key == null || issueTypeId == null || issueTypeName == null || statusId == null || statusName == null) {
            throw JiraRequestError("Jira 이슈 응답 형식이 올바르지 않습니다.")
        }

        return JiraIssue(key, issueTypeId, issueTypeName, statusId, statusName, fields.stringOrNull("summary").orEmpty())
    }

    override fun currentSprintTasks(projectKey: String, taskIssueTypeId: String): List<SprintTask> {
        // 상태로 거르지 않는다. 끝난 작업도 목록에 담아 두고 화면이 아래로 내린다(RULE.md 6.1).
        val jql = """project = "$projectKey" AND issuetype = $taskIssueTypeId """ +
            "AND assignee = currentUser() AND sprint IN openSprints() ORDER BY key ASC"

        return searchIssues(jql, MAX_SPRINT_TASKS) { key, summary, status ->
            SprintTask(key, summary, status.name, status.category, inOpenSprint = true)
        }
    }

    override fun task(issueKey: String): SprintTask? {
        val body = try {
            requestObject("/rest/api/3/issue/${encode(issueKey)}?fields=summary,status")
        } catch (error: JiraRequestError) {
            // 없는 키와 볼 수 없는 키를 구분하지 않는다. 어느 쪽이든 가드가 할 말은 같다.
            return null
        }
        val fields = body.objectOrNull("fields")
        val key = body.stringOrNull("key") ?: return null
        val status = decodeStatus(fields?.objectOrNull("status"))

        return SprintTask(
            key = key,
            summary = fields?.stringOrNull("summary").orEmpty(),
            statusName = status.name,
            statusCategory = status.category,
            inOpenSprint = isInOpenSprint(key),
        )
    }

    override fun searchByText(projectKey: String, text: String, limit: Int): List<SprintTask> {
        // 담당자·스프린트·상태로 좁히지 않는다. 끝난 작업을 다시 보는 것이 검색의 주된 쓰임이다.
        val jql = """project = "$projectKey" AND summary ~ "${escapeJqlText(text)}" ORDER BY updated DESC"""

        return searchIssues(jql, limit) { key, summary, status ->
            SprintTask(key, summary, status.name, status.category, inOpenSprint = false)
        }
    }

    /**
     * 이 이슈가 지금 열려 있는 스프린트에 담겨 있는지. 스프린트는 사이트마다 커스텀 필드 ID가 달라
     * 필드를 직접 읽을 수 없으므로, 필드 ID가 필요 없는 JQL 함수로 되묻는다.
     *
     * 확인할 수 없으면 `true`를 준다. 커밋 가드는 이 값이 `false`일 때만 경고하므로,
     * 모르는 것을 위반으로 바꾸지 않기 위해서다(RULE.md 5.2).
     */
    private fun isInOpenSprint(issueKey: String): Boolean = try {
        searchIssues("""key = "$issueKey" AND sprint IN openSprints()""", 1) { _, _, _ -> Unit }.isNotEmpty()
    } catch (error: Exception) {
        true
    }

    private fun <T> searchIssues(
        jql: String,
        limit: Int,
        decode: (key: String, summary: String, status: DecodedStatus) -> T,
    ): List<T> {
        val results = mutableListOf<T>()
        var pageToken: String? = null

        while (results.size < limit) {
            val pageSize = minOf(SEARCH_PAGE_SIZE, limit - results.size)
            val path = StringBuilder("/rest/api/3/search/jql?jql=${encode(jql)}")
                .append("&fields=summary,status&maxResults=$pageSize")
                .apply { pageToken?.let { append("&nextPageToken=${encode(it)}") } }
                .toString()

            val body = requestObject(path)
            val issues = body.arrayOrNull("issues")
                ?: throw JiraRequestError("Jira 검색 응답 형식이 올바르지 않습니다.")

            for (element in issues) {
                val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val key = obj.stringOrNull("key") ?: continue
                val fields = obj.objectOrNull("fields")
                results += decode(key, fields?.stringOrNull("summary").orEmpty(), decodeStatus(fields?.objectOrNull("status")))
            }

            // 토큰이 없으면 마지막 페이지다. 빈 페이지에 토큰만 오면 멈춘다 — 무한 루프를 만들지 않는다.
            pageToken = body.stringOrNull("nextPageToken")?.takeIf { issues.size() > 0 } ?: return results
        }

        return results
    }

    private fun decodeStatus(status: JsonObject?): DecodedStatus = DecodedStatus(
        name = status?.stringOrNull("name").orEmpty(),
        category = JiraStatusCategory.ofKey(status?.objectOrNull("statusCategory")?.stringOrNull("key")),
    )

    private data class DecodedStatus(val name: String, val category: JiraStatusCategory)

    /** JQL 문자열 리터럴 안에서 따옴표와 역슬래시를 막는다. 검색어는 사용자가 자유롭게 친다. */
    private fun escapeJqlText(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"")

    override fun verifyIdentity() {
        request("/rest/api/3/myself")
    }

    override fun listProjects(): List<JiraProject> {
        val projects = mutableListOf<JiraProject>()
        var startAt = 0

        while (true) {
            val body = requestObject("/rest/api/3/project/search?startAt=$startAt&maxResults=50")
            val values = body.arrayOrNull("values")
            val isLast = body.booleanOrNull("isLast")
            if (values == null || isLast == null) {
                throw JiraRequestError("Jira 프로젝트 목록 응답 형식이 올바르지 않습니다.")
            }
            if (!isLast && values.size() == 0) {
                throw JiraRequestError("Jira 프로젝트 목록의 다음 페이지를 확인할 수 없습니다.")
            }

            for (element in values) {
                val obj = element.takeIf { it.isJsonObject }?.asJsonObject
                val id = obj?.stringOrNull("id")
                val key = obj?.stringOrNull("key")
                val name = obj?.stringOrNull("name")
                if (id == null || key == null || name == null) {
                    throw JiraRequestError("Jira 프로젝트 목록 응답 형식이 올바르지 않습니다.")
                }
                projects.add(JiraProject(id, key, name))
            }

            if (isLast) {
                return projects
            }
            // 요청한 maxResults가 아니라 실제로 받은 개수만큼 넘긴다(서버가 더 적게 줄 수 있다).
            startAt += values.size()
        }
    }

    override fun listIssueTypesWithStatuses(projectKey: String): List<JiraIssueTypeWithStatuses> {
        val body = requestJson("/rest/api/3/project/${encode(projectKey)}/statuses")
        val array = body.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw JiraRequestError("Jira 이슈 유형 및 상태 응답 형식이 올바르지 않습니다.")

        return array.map(::decodeIssueTypeWithStatuses)
    }

    override fun listTransitions(issueKey: String): List<JiraTransition> {
        val body = requestObject("/rest/api/3/issue/${encode(issueKey)}/transitions")
        val transitions = body.arrayOrNull("transitions")
            ?: throw JiraRequestError("Jira 전환 응답 형식이 올바르지 않습니다.")

        return transitions.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = obj.stringOrNull("id") ?: return@mapNotNull null
            val target = obj.objectOrNull("to") ?: return@mapNotNull null
            val targetId = target.stringOrNull("id") ?: return@mapNotNull null

            JiraTransition(id, targetId, target.stringOrNull("name").orEmpty())
        }
    }

    override fun transition(issueKey: String, transitionId: String) {
        val payload = JsonObject().apply {
            add("transition", JsonObject().apply { addProperty("id", transitionId) })
        }
        request(
            "/rest/api/3/issue/${encode(issueKey)}/transitions",
            method = "POST",
            body = Gson().toJson(payload),
            extraHeaders = mapOf("Content-Type" to "application/json"),
        )
    }

    private fun requestObject(path: String): JsonObject = parseObject(request(path))

    private fun requestJson(path: String): JsonElement = parseJson(request(path))

    private fun request(
        path: String,
        method: String = "GET",
        body: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String {
        val headers = mapOf("Accept" to "application/json", "Authorization" to authorization) + extraHeaders
        val response = try {
            transport.send("$baseUrl$path", method, body, headers)
        } catch (error: Exception) {
            throw JiraNetworkError()
        }

        return when {
            response.statusCode in 200..299 -> response.body
            response.statusCode == 401 || response.statusCode == 403 -> throw JiraAuthenticationError()
            response.statusCode >= 500 -> throw JiraNetworkError()
            response.statusCode == 404 -> throw JiraRequestError("Jira 이슈 또는 전환을 찾을 수 없습니다.")
            else -> throw JiraRequestError("Jira 요청이 워크플로 규칙을 만족하지 않습니다.")
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    private fun parseJson(text: String): JsonElement = try {
        JsonParser.parseString(text)
    } catch (error: Exception) {
        throw JiraRequestError("Jira 응답 형식이 올바르지 않습니다.")
    }

    private fun parseObject(text: String): JsonObject =
        parseJson(text).takeIf { it.isJsonObject }?.asJsonObject
            ?: throw JiraRequestError("Jira 응답 형식이 올바르지 않습니다.")

    private fun decodeIssueTypeWithStatuses(element: JsonElement): JiraIssueTypeWithStatuses {
        val obj = element.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw JiraRequestError("Jira 이슈 유형 및 상태 응답 형식이 올바르지 않습니다.")

        val id = obj.stringOrNull("id")
        val name = obj.stringOrNull("name")
        val statuses = obj.arrayOrNull("statuses")
        if (id == null || name == null || statuses == null) {
            throw JiraRequestError("Jira 이슈 유형 및 상태 응답 형식이 올바르지 않습니다.")
        }

        val decodedStatuses = statuses.map { statusElement ->
            val statusObj = statusElement.takeIf { it.isJsonObject }?.asJsonObject
            val statusId = statusObj?.stringOrNull("id")
            val statusName = statusObj?.stringOrNull("name")
            if (statusId == null || statusName == null) {
                throw JiraRequestError("Jira 이슈 유형 및 상태 응답 형식이 올바르지 않습니다.")
            }
            JiraNamedValue(statusId, statusName)
        }

        return JiraIssueTypeWithStatuses(id, name, decodedStatuses)
    }
}

private const val MAX_SPRINT_TASKS = 200

private const val SEARCH_PAGE_SIZE = 100

private fun JsonObject.objectOrNull(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.arrayOrNull(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonObject.stringOrNull(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.booleanOrNull(key: String): Boolean? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
