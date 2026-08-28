package io.github.gudwns1812.jiratask.infrastructure

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.gudwns1812.jiratask.domain.mr.CreatedMergeRequest
import io.github.gudwns1812.jiratask.domain.mr.GitLabAuthenticationError
import io.github.gudwns1812.jiratask.domain.mr.GitLabError
import io.github.gudwns1812.jiratask.domain.mr.GitLabNetworkError
import io.github.gudwns1812.jiratask.domain.mr.MergeRequestCommand
import io.github.gudwns1812.jiratask.domain.mr.MergeRequestPort
import io.github.gudwns1812.jiratask.domain.mr.OpenMergeRequest
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * GitLab REST v4. Jira 쪽과 같은 얇은 방식이다 — 전송을 갈아 끼울 수 있게 두고 응답은 직접 읽는다.
 *
 * 토큰은 `PRIVATE-TOKEN` 헤더로 보낸다. 개인 액세스 토큰과 프로젝트 액세스 토큰이 모두 이 헤더를 쓴다.
 */
class GitLabClient(
    baseUrl: String,
    private val token: String,
    private val transport: JiraHttpTransport = HttpClientGitLabTransport(),
) : MergeRequestPort {

    private val apiBase = baseUrl.trimEnd('/') + "/api/v4"

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

    override fun defaultBranch(projectPath: String): String? = try {
        parseObject(request("/projects/${encode(projectPath)}")).text("default_branch")
    } catch (error: Exception) {
        // 기본 브랜치를 못 읽어도 사용자가 대상 브랜치를 고를 수 있다. 여기서 실패시키지 않는다.
        null
    }

    override fun create(command: MergeRequestCommand): CreatedMergeRequest {
        val payload = mapOf(
            "source_branch" to command.sourceBranch,
            "target_branch" to command.targetBranch,
            "title" to command.title,
            "description" to command.description,
        )
        val body = request(
            "/projects/${encode(command.projectPath)}/merge_requests",
            method = "POST",
            body = Gson().toJson(payload),
        )
        val webUrl = parseObject(body).text("web_url")
            ?: throw GitLabError("GitLab이 만든 MR 주소를 알려주지 않았습니다.")

        return CreatedMergeRequest(webUrl)
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
            response.statusCode == 401 || response.statusCode == 403 -> throw GitLabAuthenticationError()
            response.statusCode == 404 -> throw GitLabError("GitLab 프로젝트를 찾을 수 없습니다. 접근 권한을 확인하세요.")
            response.statusCode >= 500 -> throw GitLabNetworkError()
            // GitLab은 규칙 위반을 400/409에 사람이 읽을 문구로 담아 준다. 그대로 보여 준다.
            else -> throw GitLabError(messageOf(response.body))
        }
    }

    /** `{"message":{"source_branch":["can't be blank"]}}` 같은 응답에서 읽을 만한 문구를 건진다. */
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
}

class HttpClientGitLabTransport(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) : JiraHttpTransport {
    override fun send(url: String, method: String, body: String?, headers: Map<String, String>): JiraHttpResponse {
        val builder = HttpRequest.newBuilder(URI(url))
        headers.forEach { (key, value) -> builder.header(key, value) }
        val publisher =
            if (body != null) HttpRequest.BodyPublishers.ofString(body) else HttpRequest.BodyPublishers.noBody()
        builder.method(method, publisher)

        val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return JiraHttpResponse(response.statusCode(), response.body())
    }
}
