package io.github.gudwns1812.jiratask.ui

import io.github.gudwns1812.jiratask.domain.mr.GitLabAuthenticationError
import io.github.gudwns1812.jiratask.domain.mr.GitLabError
import io.github.gudwns1812.jiratask.domain.mr.GitLabIdentity
import io.github.gudwns1812.jiratask.domain.mr.GitLabNetworkError
import io.github.gudwns1812.jiratask.domain.mr.GitLabPermissionError

/** 연결 테스트가 화면에 내놓을 한 줄. */
data class GitLabConnectionReport(val message: String, val isSuccess: Boolean)

/**
 * 설정 화면의 "연결 테스트"가 무엇을 말할지 정한다.
 *
 * 화면(`Configurable`)에서 갈라 둔 이유는 헤드리스 테스트 때문이다 — `Configurable`은 IDE 없이
 * 띄울 수 없어서, 여기 두지 않으면 어떤 실패에 어떤 안내가 나가는지 아무도 검증하지 못한다.
 *
 * 토큰 값은 어떤 경로로도 문구에 담지 않는다(ARCHITECTURE.md 191, 226).
 */
object GitLabConnectionCheck {

    /** 부르기 전에 입력이 충분한지. 부족하면 이유, 충분하면 null. */
    fun inputProblem(baseUrl: String, hasToken: Boolean): String? = when {
        baseUrl.isBlank() -> NO_BASE_URL
        !baseUrl.startsWith("http://") && !baseUrl.startsWith("https://") -> NOT_HTTP
        !hasToken -> NO_TOKEN
        else -> null
    }

    fun of(identity: GitLabIdentity): GitLabConnectionReport {
        val connected = "✓ 연결됨: ${identity.username}"

        // 조회는 되지만 MR 생성은 못 하는 토큰이다. 연결 자체는 성공이므로 막지 않고 알리기만 한다.
        return if (identity.lacksApiScope) {
            GitLabConnectionReport("$connected — $NEEDS_API_SCOPE", isSuccess = true)
        } else {
            GitLabConnectionReport(connected, isSuccess = true)
        }
    }

    /** 실패를 무엇으로 읽어 줄지. 원인마다 고칠 곳이 다르므로 뭉뚱그리지 않는다. */
    fun of(error: Throwable): GitLabConnectionReport = GitLabConnectionReport(
        message = when (error) {
            is GitLabAuthenticationError -> "✗ ${error.message} 토큰을 다시 발급해 넣으세요."
            is GitLabPermissionError -> "✗ ${error.message}"
            is GitLabNetworkError -> "✗ ${error.message} 주소와 네트워크를 확인하세요."
            is GitLabError -> "✗ ${error.message}"
            else -> "✗ $UNKNOWN"
        },
        isSuccess = false,
    )

    const val NO_BASE_URL: String = "GitLab 주소를 먼저 입력하세요."
    const val NOT_HTTP: String = "GitLab 주소는 http 또는 https로 시작해야 합니다."
    const val NO_TOKEN: String =
        "확인할 토큰이 없습니다. 액세스 토큰을 입력하거나 저장한 뒤 다시 시도하세요."
    const val NEEDS_API_SCOPE: String =
        "이 토큰에는 api 스코프가 없어 MR을 만들 수 없습니다. 조회만 됩니다."
    const val UNKNOWN: String = "GitLab 연결을 확인하지 못했습니다."
}
