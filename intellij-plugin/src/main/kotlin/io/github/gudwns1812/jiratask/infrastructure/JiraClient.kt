package io.github.gudwns1812.jiratask.infrastructure

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.gudwns1812.jiratask.domain.jira.JiraAuthenticationError
import io.github.gudwns1812.jiratask.domain.jira.JiraCreatedIssue
import io.github.gudwns1812.jiratask.domain.jira.JiraDraftReader
import io.github.gudwns1812.jiratask.domain.jira.JiraFieldIds
import io.github.gudwns1812.jiratask.domain.jira.JiraIssueCreator
import io.github.gudwns1812.jiratask.domain.jira.JiraIssue
import io.github.gudwns1812.jiratask.domain.jira.JiraIssueTypeWithStatuses
import io.github.gudwns1812.jiratask.domain.jira.JiraNamedValue
import io.github.gudwns1812.jiratask.domain.jira.JiraNetworkError
import io.github.gudwns1812.jiratask.domain.jira.JiraProject
import io.github.gudwns1812.jiratask.domain.jira.JiraRequestError
import io.github.gudwns1812.jiratask.domain.jira.JiraSetupReader
import io.github.gudwns1812.jiratask.domain.jira.JiraStoryContext
import io.github.gudwns1812.jiratask.domain.jira.StoryIssueReader
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
) : StoryIssueReader, JiraSetupReader, JiraDraftReader, JiraIssueCreator {

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

    override fun currentUserName(): String? = try {
        requestObject("/rest/api/3/myself").stringOrNull("displayName")
    } catch (error: JiraRequestError) {
        null
    }

    override fun listPriorities(): List<String> = try {
        val json = requestJson("/rest/api/3/priority")
        (json as? JsonArray)?.mapNotNull { it.takeIf { item -> item.isJsonObject }?.asJsonObject?.stringOrNull("name") }
            ?: emptyList()
    } catch (error: JiraRequestError) {
        emptyList()
    }

    /**
     * 스토리에서 물려받을 값. 스프린트는 Jira 사이트마다 커스텀 필드 ID가 달라 REST v3로는 안정적으로
     * 읽을 수 없으므로, 이름이 고정된 Agile API를 먼저 쓰고 실패하면 v3의 `parent`만 읽는다.
     */
    override fun storyContext(storyKey: String): JiraStoryContext {
        agileStoryContext(storyKey)?.let { return it }

        val fields = requestObject("/rest/api/3/issue/${encode(storyKey)}?fields=summary,parent").objectOrNull("fields")
        return JiraStoryContext(
            summary = fields?.stringOrNull("summary"),
            epicKey = fields?.objectOrNull("parent")?.stringOrNull("key"),
            sprint = null,
        )
    }

    private fun agileStoryContext(storyKey: String): JiraStoryContext? = try {
        val fields = requestObject("/rest/agile/1.0/issue/${encode(storyKey)}?fields=summary,epic,sprint,parent")
            .objectOrNull("fields") ?: return null
        JiraStoryContext(
            summary = fields.stringOrNull("summary"),
            epicKey = fields.objectOrNull("epic")?.stringOrNull("key")
                ?: fields.objectOrNull("parent")?.stringOrNull("key"),
            sprint = fields.objectOrNull("sprint")?.stringOrNull("name"),
            sprintId = fields.objectOrNull("sprint")?.get("id")?.takeIf { it.isJsonPrimitive }?.asInt,
        )
    } catch (error: JiraRequestError) {
        null
    }

    override fun createIssue(fields: Map<String, Any?>): JiraCreatedIssue {
        val body = gson.toJson(mapOf("fields" to fields))
        val response = parseObject(
            request("/rest/api/3/issue", method = "POST", body = body, extraHeaders = JSON_CONTENT_TYPE),
        )
        val key = response.stringOrNull("key")
            ?: throw JiraRequestError("Jira가 만든 이슈 키를 알려주지 않았습니다.")
        return JiraCreatedIssue(key)
    }

    override fun findAccountId(query: String): String? {
        val json = requestJson("/rest/api/3/user/search?query=${encode(query)}")
        val users = (json as? JsonArray) ?: return null
        return users.asSequence()
            .mapNotNull { it.takeIf { user -> user.isJsonObject }?.asJsonObject }
            .mapNotNull { it.stringOrNull("accountId") }
            .firstOrNull()
    }

    /** 스토리 포인트·스프린트는 사이트마다 커스텀 필드 ID가 달라 이름으로 찾아야 한다. */
    override fun findFieldIds(): JiraFieldIds {
        val json = requestJson("/rest/api/3/field")
        val fields = (json as? JsonArray) ?: return JiraFieldIds()
        val byName = fields.asSequence()
            .mapNotNull { it.takeIf { field -> field.isJsonObject }?.asJsonObject }
            .mapNotNull { field ->
                val id = field.stringOrNull("id") ?: return@mapNotNull null
                val name = field.stringOrNull("name")?.trim()?.lowercase() ?: return@mapNotNull null
                name to id
            }
            .toMap()

        return JiraFieldIds(
            storyPoints = STORY_POINT_FIELD_NAMES.firstNotNullOfOrNull { byName[it] },
            sprint = byName["sprint"],
        )
    }

    override fun findSprintId(projectKey: String, sprintName: String): Int? {
        val matchingIds = linkedSetOf<Int>()
        for (boardId in boardIds(projectKey)) {
            var startAt = 0
            while (true) {
                val page = requestObject(
                    "/rest/agile/1.0/board/$boardId/sprint?state=active,future&startAt=$startAt&maxResults=50",
                )
                val sprints = page.arrayOrNull("values")
                    ?: throw JiraRequestError("Jira 스프린트 목록 응답 형식이 올바르지 않습니다.")
                sprints.asSequence()
                    .mapNotNull { it.takeIf { sprint -> sprint.isJsonObject }?.asJsonObject }
                    .filter { it.stringOrNull("name") == sprintName }
                    .mapNotNull { it.intOrNull("id") }
                    .forEach { matchingIds += it }
                if (matchingIds.size > 1) {
                    return null
                }

                val isLast = page.booleanOrNull("isLast")
                    ?: throw JiraRequestError("Jira 스프린트 목록 응답 형식이 올바르지 않습니다.")
                if (isLast) {
                    break
                }
                if (sprints.isEmpty) {
                    throw JiraRequestError("Jira 스프린트 목록의 다음 페이지를 확인할 수 없습니다.")
                }
                startAt += sprints.size()
            }
        }
        return matchingIds.singleOrNull()
    }

    private fun boardIds(projectKey: String): List<Int> {
        val ids = mutableListOf<Int>()
        var startAt = 0
        while (true) {
            val page = requestObject(
                "/rest/agile/1.0/board?projectKeyOrId=${encode(projectKey)}&startAt=$startAt&maxResults=50",
            )
            val boards = page.arrayOrNull("values")
                ?: throw JiraRequestError("Jira 보드 목록 응답 형식이 올바르지 않습니다.")
            ids += boards.mapNotNull { it.takeIf { board -> board.isJsonObject }?.asJsonObject?.intOrNull("id") }

            val isLast = page.booleanOrNull("isLast")
                ?: throw JiraRequestError("Jira 보드 목록 응답 형식이 올바르지 않습니다.")
            if (isLast) {
                return ids
            }
            if (boards.isEmpty) {
                throw JiraRequestError("Jira 보드 목록의 다음 페이지를 확인할 수 없습니다.")
            }
            startAt += boards.size()
        }
    }

    override fun linkIssues(inwardKey: String, outwardKey: String, linkTypeName: String) {
        val body = gson.toJson(
            mapOf(
                "type" to mapOf("name" to linkTypeName),
                "inwardIssue" to mapOf("key" to inwardKey),
                "outwardIssue" to mapOf("key" to outwardKey),
            ),
        )
        request("/rest/api/3/issueLink", method = "POST", body = body, extraHeaders = JSON_CONTENT_TYPE)
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
            startAt += 50
        }
    }

    override fun listIssueTypesWithStatuses(projectKey: String): List<JiraIssueTypeWithStatuses> {
        val body = requestJson("/rest/api/3/project/${encode(projectKey)}/statuses")
        val array = body.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw JiraRequestError("Jira 이슈 유형 및 상태 응답 형식이 올바르지 않습니다.")

        return array.map(::decodeIssueTypeWithStatuses)
    }

    override fun findTransitionId(issueKey: String, targetStatusId: String): String? {
        val body = requestObject("/rest/api/3/issue/${encode(issueKey)}/transitions")
        val transitions = body.arrayOrNull("transitions")
            ?: throw JiraRequestError("Jira 전환 응답 형식이 올바르지 않습니다.")

        for (element in transitions) {
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val id = obj.stringOrNull("id")
            val targetId = obj.objectOrNull("to")?.stringOrNull("id")
            if (id != null && targetId == targetStatusId) {
                return id
            }
        }

        return null
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

    private val gson = Gson()

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

private val JSON_CONTENT_TYPE = mapOf("Content-Type" to "application/json")

/** 팀 관리형·회사 관리형 프로젝트에서 스토리 포인트 필드 이름이 다르다. */
private val STORY_POINT_FIELD_NAMES = listOf("story points", "story point estimate")

private fun JsonObject.objectOrNull(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.arrayOrNull(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonObject.stringOrNull(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.booleanOrNull(key: String): Boolean? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

private fun JsonObject.intOrNull(key: String): Int? =
    get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }
