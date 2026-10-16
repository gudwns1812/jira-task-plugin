package io.github.gudwns1812.jiratask.infrastructure

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.gudwns1812.jiratask.domain.mr.CreatedMergeRequest
import io.github.gudwns1812.jiratask.domain.mr.GitLabAuthenticationError
import io.github.gudwns1812.jiratask.domain.mr.GitLabError
import io.github.gudwns1812.jiratask.domain.mr.GitLabIdentity
import io.github.gudwns1812.jiratask.domain.mr.GitLabNetworkError
import io.github.gudwns1812.jiratask.domain.mr.GitLabPermissionError
import io.github.gudwns1812.jiratask.domain.mr.GitLabProjectInfo
import io.github.gudwns1812.jiratask.domain.mr.GitLabUser
import io.github.gudwns1812.jiratask.domain.mr.MergeRequestCommand
import io.github.gudwns1812.jiratask.domain.mr.MergeRequestPort
import io.github.gudwns1812.jiratask.domain.mr.OpenMergeRequest
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class GitLabClient(
    baseUrl: String,
    private val token: String,
    private val transport: JiraHttpTransport = HttpClientGitLabTransport(),
) : MergeRequestPort {

    private val apiBase = baseUrl.trimEnd('/') + "/api/v4"

    override fun verifyIdentity(): GitLabIdentity {
        val user = parseObject(request("/user"))
        val username = user.text("username") ?: user.text("name") ?: "(이름 없음)"

        return GitLabIdentity(username = username).withScopes()
    }

    private fun GitLabIdentity.withScopes(): GitLabIdentity {
        val scopes = try {
            parseObject(request("/personal_access_tokens/self")).get("scopes")
                ?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.takeIf { s -> s.isJsonPrimitive }?.asString }
                ?: return this
        } catch (error: Exception) {
            return this
        }

        return copy(scopesKnown = true, canCreateMergeRequest = scopes.contains(API_SCOPE))
    }

    override fun openMergeRequest(projectPath: String, sourceBranch: String): OpenMergeRequest? {
        val body = request(
            "/projects/${encode(projectPath)}/merge_requests" +
                "?state=opened&source_branch=${encode(sourceBranch)}",
        )
        val first = parse(body).takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return null

        return OpenMergeRequest(
            webUrl = first.text("web_url").orEmpty(),
            title = first.text("title").orEmpty(),
        )
    }

    override fun projectInfo(projectPath: String): GitLabProjectInfo = try {
        val project = parseObject(request("/projects/${encode(projectPath)}"))
        GitLabProjectInfo(
            defaultBranch = project.text("default_branch"),
            mergeRequestTemplate = project.text("merge_requests_template"),
        )
    } catch (error: Exception) {
        GitLabProjectInfo()
    }

    override fun create(command: MergeRequestCommand): CreatedMergeRequest {
        val payload = buildMap<String, Any> {
            put("source_branch", command.sourceBranch)
            put("target_branch", command.targetBranch)
            put("title", command.title)
            put("description", command.description)
            command.assigneeIds.takeIf { it.isNotEmpty() }?.let { put("assignee_ids", it) }
            command.reviewerIds.takeIf { it.isNotEmpty() }?.let { put("reviewer_ids", it) }
            command.labels.takeIf { it.isNotEmpty() }?.let { put("labels", it.joinToString(",")) }
        }
        val body = request(
            "/projects/${encode(command.projectPath)}/merge_requests",
            method = "POST",
            body = Gson().toJson(payload),
        )
        return CreatedMergeRequest(parseObject(body).text("web_url"))
    }

    override fun me(): GitLabUser? = try {
        userOf(parseObject(request("/user")))
    } catch (error: Exception) {
        null
    }

    override fun labels(projectPath: String): List<String> = try {
        parse(request("/projects/${encode(projectPath)}/labels?per_page=$PAGE_SIZE"))
            .takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { label -> label.isJsonObject }?.asJsonObject?.text("name") }
            ?.distinct()
            .orEmpty()
    } catch (error: Exception) {
        emptyList()
    }

    override fun findUsers(projectPath: String, query: String): List<GitLabUser> = try {
        val search = query.trim().takeIf { it.isNotEmpty() }?.let { "&search=${encode(it)}" }.orEmpty()
        parse(request("/projects/${encode(projectPath)}/users?per_page=$PAGE_SIZE$search"))
            .takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { user -> user.isJsonObject }?.asJsonObject?.let(::userOf) }
            .orEmpty()
    } catch (error: Exception) {
        emptyList()
    }

    override fun searchUsers(query: String): List<GitLabUser> = try {
        val search = query.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        parse(request("/users?per_page=$PAGE_SIZE&search=${encode(search)}"))
            .takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { user -> user.isJsonObject }?.asJsonObject?.let(::userOf) }
            .orEmpty()
    } catch (error: Exception) {
        emptyList()
    }

    private fun userOf(json: JsonObject): GitLabUser? {
        val id = json.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: return null
        val username = json.text("username") ?: return null

        return GitLabUser(id = id, username = username, name = json.text("name").orEmpty())
    }

    private fun request(path: String, method: String = "GET", body: String? = null): String {
        val headers = mutableMapOf("Accept" to "application/json", "PRIVATE-TOKEN" to token)
        if (body != null) {
            headers["Content-Type"] = "application/json"
        }

        val response = try {
            transport.send("$apiBase$path", method, body, headers)
        } catch (error: Exception) {
            throw GitLabNetworkError()
        }

        return when {
            response.statusCode in 200..299 -> response.body
            response.statusCode == 401 -> throw GitLabAuthenticationError()
            response.statusCode == 403 -> throw GitLabPermissionError()
            response.statusCode == 404 -> throw GitLabError("GitLab 프로젝트를 찾을 수 없습니다. 접근 권한을 확인하세요.")
            response.statusCode >= 500 -> throw GitLabNetworkError()
            else -> throw GitLabError(messageOf(response.body))
        }
    }

    private fun messageOf(body: String): String {
        val fallback = "GitLab 요청이 거부되었습니다."
        val json = runCatching { parse(body) }.getOrNull() ?: return fallback
        val node = (json as? JsonObject)?.get("message") ?: (json as? JsonObject)?.get("error") ?: return fallback

        return when {
            node.isJsonPrimitive -> node.asString
            node.isJsonArray -> node.asJsonArray.joinToString(" ") { it.toString().trim('"') }
            node.isJsonObject -> node.asJsonObject.entrySet()
                .joinToString(" ") { (key, value) -> "$key ${value.toString().trim('[', ']', '"')}" }
            else -> fallback
        }.ifBlank { fallback }
    }

    private fun parse(text: String): JsonElement = try {
        JsonParser.parseString(text)
    } catch (error: Exception) {
        throw GitLabError("GitLab 응답 형식이 올바르지 않습니다.")
    }

    private fun parseObject(text: String): JsonObject =
        parse(text).takeIf { it.isJsonObject }?.asJsonObject
            ?: throw GitLabError("GitLab 응답 형식이 올바르지 않습니다.")

    private fun JsonObject.text(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    private companion object {
        const val API_SCOPE = "api"

        const val PAGE_SIZE = 100
    }
}

class HttpClientGitLabTransport(
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
) : JiraHttpTransport {
    override fun send(url: String, method: String, body: String?, headers: Map<String, String>): JiraHttpResponse {
        val builder = HttpRequest.newBuilder(URI(url)).timeout(REQUEST_TIMEOUT)
        headers.forEach { (key, value) -> builder.header(key, value) }
        val publisher =
            if (body != null) HttpRequest.BodyPublishers.ofString(body) else HttpRequest.BodyPublishers.noBody()
        builder.method(method, publisher)

        val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return JiraHttpResponse(response.statusCode(), response.body())
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
