package io.github.gudwns1812.jiratask.domain.mr

object GitLabBaseUrl {

    private val SCHEME_AND_HOST = Regex("""^([A-Za-z][A-Za-z0-9+.-]*://)([^/]+)(.*)$""")

    fun normalize(raw: String?): String {
        val trimmed = raw?.trim()?.trimEnd('/').orEmpty()
        if (trimmed.isEmpty()) {
            return ""
        }

        val match = SCHEME_AND_HOST.matchEntire(trimmed) ?: return trimmed.lowercase()
        val (scheme, host, path) = match.destructured

        return scheme.lowercase() + host.lowercase() + path
    }
}
