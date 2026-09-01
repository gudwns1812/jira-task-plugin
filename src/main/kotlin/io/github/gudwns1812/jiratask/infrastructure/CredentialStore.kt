package io.github.gudwns1812.jiratask.infrastructure

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import io.github.gudwns1812.jiratask.application.CredentialStore
import io.github.gudwns1812.jiratask.domain.mr.GitLabBaseUrl

private const val SERVICE_NAME = "jira-task-plugin"

class CredentialStorageError :
    Exception("운영체제 자격 증명 저장소에 API 토큰을 저장하거나 읽지 못했습니다.")

/** GitLab 개인 액세스 토큰. Jira 토큰과 같은 저장소를 쓰되 계정 이름으로 갈라 둔다. */
class GitLabTokenStore(private val passwordSafe: () -> PasswordSafe = { PasswordSafe.instance }) {

    fun save(baseUrl: String, token: String) {
        try {
            val account = gitLabAccount(baseUrl)
            passwordSafe().set(
                CredentialAttributes(generateServiceName(SERVICE_NAME, account)),
                Credentials(account, token),
            )
        } catch (error: Exception) {
            throw CredentialStorageError()
        }
    }

    fun get(baseUrl: String): String? = try {
        passwordSafe().getPassword(CredentialAttributes(generateServiceName(SERVICE_NAME, gitLabAccount(baseUrl))))
    } catch (error: Exception) {
        throw CredentialStorageError()
    }

    // 저장할 때와 찾을 때가 같은 모양을 써야 한다. 어긋나면 넣어 둔 토큰이 없는 것처럼 보인다.
    private fun gitLabAccount(baseUrl: String): String = "gitlab|${GitLabBaseUrl.normalize(baseUrl)}"
}

/**
 * API 토큰은 설정 파일과 분리해 IntelliJ `PasswordSafe`(운영체제 자격 증명 저장소)에 보관한다.
 * [CredentialStore] 포트는 애플리케이션 계층([io.github.gudwns1812.jiratask.application.runInitialSetup])이
 * 정의하고, 여기서는 그 구현만 제공한다.
 */
class PasswordSafeCredentialStore(
    private val passwordSafe: PasswordSafe = PasswordSafe.instance,
) : CredentialStore {

    override fun save(baseUrl: String, email: String, apiToken: String) {
        try {
            val account = accountName(baseUrl, email)
            passwordSafe.set(credentialAttributes(account), Credentials(account, apiToken))
        } catch (error: Exception) {
            throw CredentialStorageError()
        }
    }

    override fun get(baseUrl: String, email: String): String? {
        try {
            return passwordSafe.getPassword(credentialAttributes(accountName(baseUrl, email)))
        } catch (error: Exception) {
            throw CredentialStorageError()
        }
    }

    private fun credentialAttributes(account: String): CredentialAttributes =
        CredentialAttributes(generateServiceName(SERVICE_NAME, account))
}

private fun accountName(baseUrl: String, email: String): String =
    "${baseUrl.trimEnd('/').lowercase()}|${email.trim().lowercase()}"
