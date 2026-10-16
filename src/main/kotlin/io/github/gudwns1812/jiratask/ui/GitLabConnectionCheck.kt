package io.github.gudwns1812.jiratask.ui

import io.github.gudwns1812.jiratask.domain.mr.GitLabAuthenticationError
import io.github.gudwns1812.jiratask.domain.mr.GitLabError
import io.github.gudwns1812.jiratask.domain.mr.GitLabIdentity
import io.github.gudwns1812.jiratask.domain.mr.GitLabNetworkError
import io.github.gudwns1812.jiratask.domain.mr.GitLabPermissionError

data class GitLabConnectionReport(val message: String, val isSuccess: Boolean)

object GitLabConnectionCheck {
    fun inputProblem(baseUrl: String, hasToken: Boolean): String? = when {
        baseUrl.isBlank() -> NO_BASE_URL
        !baseUrl.startsWith("http://") && !baseUrl.startsWith("https://") -> NOT_HTTP
        !hasToken -> NO_TOKEN
        else -> null
    }

    fun of(identity: GitLabIdentity): GitLabConnectionReport {
        val connected = "✓ 연결됨: ${identity.username}"

        return if (identity.lacksApiScope) {
            GitLabConnectionReport("$connected — $NEEDS_API_SCOPE", isSuccess = true)
        } else {
            GitLabConnectionReport(connected, isSuccess = true)
        }
    }

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
