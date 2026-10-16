package io.github.gudwns1812.jiratask.infrastructure

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.gudwns1812.jiratask.domain.jira.AdfDocument
import io.github.gudwns1812.jiratask.domain.jira.JiraAuthenticationError
import io.github.gudwns1812.jiratask.domain.jira.JiraIssue
import io.github.gudwns1812.jiratask.domain.jira.JiraIssueDetail
import io.github.gudwns1812.jiratask.domain.jira.JiraIssueEditor
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
) : JiraSetupReader, TaskIssueTransitioner, SprintTaskReader, JiraIssueEditor {

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

        return JiraIssue(
            key = key,
            issueTypeId = issueTypeId,
            issueTypeName = issueTypeName,
            statusId = statusId,
            statusName = statusName,
            summary = fields.stringOrNull("summary").orEmpty(),
            statusCategory = decodeStatus(status).category,
        )
    }

    override fun currentSprintTasks(projectKey: String, taskIssueTypeId: String): List<SprintTask> {
        val jql = """project = "$projectKey" AND issuetype = $taskIssueTypeId """ +
            "AND assignee = currentUser() AND sprint IN openSprints() ORDER BY created ASC"

        return searchIssues(jql, MAX_SPRINT_TASKS) { key, summary, status ->
            SprintTask(key, summary, status.name, status.category, inOpenSprint = true)
        }
    }

    override fun countTasks(
        projectKey: String,
        taskIssueTypeId: String,
        mineOnly: Boolean,
        openSprintOnly: Boolean,
    ): Int {
        val jql = buildString {
            append("""project = "$projectKey" AND issuetype = $taskIssueTypeId""")
            if (mineOnly) append(" AND assignee = currentUser()")
            if (openSprintOnly) append(" AND sprint IN openSprints()")
        }

        return searchIssues(jql, MAX_SPRINT_TASKS) { _, _, _ -> Unit }.size
    }

    override fun task(issueKey: String): SprintTask? {
        val body = try {
            requestObject("/rest/api/3/issue/${encode(issueKey)}?fields=summary,status")
        } catch (error: JiraRequestError) {
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
        val jql = """project = "$projectKey" AND summary ~ "${escapeJqlText(text)}" ORDER BY updated DESC"""

        return searchIssues(jql, limit) { key, summary, status ->
            SprintTask(key, summary, status.name, status.category, inOpenSprint = false)
        }
    }

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

            pageToken = body.stringOrNull("nextPageToken")?.takeIf { issues.size() > 0 } ?: return results
        }

        return results
    }

    private fun decodeStatus(status: JsonObject?): DecodedStatus = DecodedStatus(
        name = status?.stringOrNull("name").orEmpty(),
        category = JiraStatusCategory.ofKey(status?.objectOrNull("statusCategory")?.stringOrNull("key")),
    )

    private data class DecodedStatus(val name: String, val category: JiraStatusCategory)

    private fun escapeJqlText(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"")

    override fun detail(issueKey: String): JiraIssueDetail? {
        val body = try {
            requestObject("/rest/api/3/issue/${encode(issueKey)}?fields=summary,description,status,assignee")
        } catch (error: JiraRequestError) {
            return null
        }
        val fields = body.objectOrNull("fields")

        return JiraIssueDetail(
            key = body.stringOrNull("key") ?: return null,
            summary = fields?.stringOrNull("summary").orEmpty(),
            description = AdfDocument.textOf(fields?.get("description")?.toKotlinValue()),
            statusName = fields?.objectOrNull("status")?.stringOrNull("name").orEmpty(),
            assignee = fields?.objectOrNull("assignee")?.stringOrNull("displayName").orEmpty(),
            sprint = sprintNameOf(issueKey),
        )
    }

    override fun updateSummaryAndDescription(issueKey: String, summary: String, description: String) {
        val payload = mapOf(
            "fields" to mapOf(
                "summary" to summary,
                "description" to AdfDocument.of(description),
            ),
        )
        request(
            "/rest/api/3/issue/${encode(issueKey)}",
            method = "PUT",
            body = Gson().toJson(payload.toJsonElement()),
            extraHeaders = mapOf("Content-Type" to "application/json"),
        )
    }

    private fun sprintNameOf(issueKey: String): String = try {
        requestObject("/rest/agile/1.0/issue/${encode(issueKey)}?fields=sprint")
            .objectOrNull("fields")?.objectOrNull("sprint")?.stringOrNull("name").orEmpty()
    } catch (error: Exception) {
        ""
    }

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

            JiraTransition(
                id = id,
                toStatusId = targetId,
                toStatusName = target.stringOrNull("name").orEmpty(),
                toStatusCategory = decodeStatus(target).category,
            )
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
