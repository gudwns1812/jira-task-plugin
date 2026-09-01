package io.github.gudwns1812.jiratask.domain.mr

/**
 * GitLab 주소를 한 가지 모양으로 맞춘다.
 *
 * 토큰은 주소를 열쇠 삼아 보관한다(`gitlab|<주소>`). 저장할 때와 찾을 때가 서로 다른 모양을 쓰면
 * 열쇠가 어긋나 **넣어 둔 토큰이 없는 것처럼 보인다.** 그래서 양쪽이 이 함수 하나만 쓴다.
 *
 * 호스트와 스킴만 소문자로 내린다. 경로까지 내리면 `https://host/GitLab` 같은 주소를 망가뜨린다 —
 * 호스트 이름은 대소문자를 가리지 않지만 경로는 가린다.
 */
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
