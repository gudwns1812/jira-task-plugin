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
import io.github.gudwns1812.jiratask.domain.mr.MergeRequestCommand
import io.github.gudwns1812.jiratask.domain.mr.MergeRequestPort
import io.github.gudwns1812.jiratask.domain.mr.OpenMergeRequest
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

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

    override fun verifyIdentity(): GitLabIdentity {
        val user = parseObject(request("/user"))
        val username = user.text("username") ?: user.text("name") ?: "(이름 없음)"

        return GitLabIdentity(username = username).withScopes()
    }

    /**
     * 토큰 스코프를 덧붙인다. 읽지 못하면 붙이지 않는다.
     *
     * 이 엔드포인트는 구버전 GitLab에 없고, 프로젝트 액세스 토큰으로는 404가 나기도 한다.
     * 스코프를 못 읽는 것은 스코프가 없는 것과 다르므로 여기서 검증을 실패시키지 않는다.
     */
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
            // 프로젝트 설정의 기본 MR 템플릿. Premium 이상에서만 채워지므로 대개 비어 있다.
            mergeRequestTemplate = project.text("merge_requests_template"),
        )
    } catch (error: Exception) {
        // 못 읽어도 사용자가 대상 브랜치와 템플릿을 고를 수 있다. 여기서 실패시키지 않는다.
        GitLabProjectInfo()
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
        // 여기까지 왔으면 MR은 이미 만들어졌다. 주소를 못 읽었다고 실패로 돌리지 않는다.
        return CreatedMergeRequest(parseObject(body).text("web_url"))
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
            // 401과 403은 고칠 곳이 다르다. `read_api`만 있는 토큰은 조회를 통과하고 MR 생성에서만 403으로 죽는다.
            response.statusCode == 401 -> throw GitLabAuthenticationError()
            response.statusCode == 403 -> throw GitLabPermissionError()
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

    private companion object {
        /** MR 생성(POST)에 필요한 스코프. `read_api`는 조회만 되고 생성에서 403으로 죽는다. */
        const val API_SCOPE = "api"
    }
}

/**
 * 반드시 시한을 둔다. "MR 생성" 작업은 취소할 수 없게 만들어져 있어서, 응답이 오지 않으면
 * 사용자가 멈출 방법이 없다. 기다리다 실패하는 편이 영원히 매달리는 것보다 낫다.
 */
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
