package io.github.gudwns1812.jiratask.domain.mr

/**
 * git remote URL에서 GitLab 프로젝트 경로를 뽑는다.
 *
 * GitLab API는 프로젝트를 숫자 ID 또는 URL 인코딩한 `group/subgroup/name` 경로로 받는다.
 * 경로 쪽이 remote만 보고 알아낼 수 있어 설정에 하나를 덜 묻는다.
 */
object GitLabProject {

    private val SSH = Regex("""^(?:ssh://)?git@([^:/]+)[:/](.+?)(?:\.git)?/?$""")
    private val HTTP = Regex("""^https?://(?:[^@/]+@)?([^/]+?)(?::\d+)?/(.+?)(?:\.git)?/?$""")

    /** remote URL의 호스트와 프로젝트 경로. GitLab이 아닌 것 같으면 null. */
    fun of(remoteUrl: String?): GitLabRemote? {
        val url = remoteUrl?.trim().orEmpty()
        if (url.isEmpty()) {
            return null
        }

        val match = SSH.matchEntire(url) ?: HTTP.matchEntire(url) ?: return null
        val host = match.groupValues[1].trim().ifEmpty { return null }
        val path = match.groupValues[2].trim('/').ifEmpty { return null }

        // `group/name`이 최소 형태다. 한 조각뿐이면 프로젝트 경로가 아니다.
        if (!path.contains('/')) {
            return null
        }

        return GitLabRemote(host, path)
    }
}

data class GitLabRemote(val host: String, val projectPath: String) {

    /** 이 remote가 [baseUrl]이 가리키는 GitLab과 같은 곳인가. */
    fun matches(baseUrl: String?): Boolean {
        val configured = baseUrl?.trim()?.removePrefix("https://")?.removePrefix("http://")
            ?.substringBefore('/')?.substringBefore(':')?.lowercase().orEmpty()

        return configured.isNotEmpty() && configured == host.lowercase()
    }
}
