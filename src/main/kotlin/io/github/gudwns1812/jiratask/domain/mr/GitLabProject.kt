package io.github.gudwns1812.jiratask.domain.mr

object GitLabProject {

    private val SSH = Regex("""^(?:ssh://)?git@([^:/]+)[:/](.+?)(?:\.git)?/?$""")
    private val HTTP = Regex("""^https?://(?:[^@/]+@)?([^/]+?)(?::\d+)?/(.+?)(?:\.git)?/?$""")

    fun of(remoteUrl: String?): GitLabRemote? {
        val url = remoteUrl?.trim().orEmpty()
        if (url.isEmpty()) {
            return null
        }

        val match = SSH.matchEntire(url) ?: HTTP.matchEntire(url) ?: return null
        val host = match.groupValues[1].trim().ifEmpty { return null }
        val path = match.groupValues[2].trim('/').ifEmpty { return null }

        if (!path.contains('/')) {
            return null
        }

        return GitLabRemote(host, path)
    }
}

data class GitLabRemote(val host: String, val projectPath: String) {
    fun matches(baseUrl: String?): Boolean {
        val configured = baseUrl?.trim()?.removePrefix("https://")?.removePrefix("http://")
            ?.substringBefore('/')?.substringBefore(':')?.lowercase().orEmpty()

        return configured.isNotEmpty() && configured == host.lowercase()
    }
}
