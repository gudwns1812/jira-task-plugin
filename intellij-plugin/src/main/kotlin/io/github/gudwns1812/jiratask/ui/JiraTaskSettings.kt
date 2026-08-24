package io.github.gudwns1812.jiratask.ui

import io.github.gudwns1812.jiratask.application.CredentialStore
import io.github.gudwns1812.jiratask.application.IssueTypeIds
import io.github.gudwns1812.jiratask.domain.policy.PolicyConfigurationError
import io.github.gudwns1812.jiratask.domain.policy.WorkPolicy
import io.github.gudwns1812.jiratask.infrastructure.CredentialStorageError
import io.github.gudwns1812.jiratask.infrastructure.PasswordSafeCredentialStore
import io.github.gudwns1812.jiratask.infrastructure.PolicyConfigRepository

/** 저장된 설정에서 명령 하나를 실행할 수 있는지 본 결과. 부족하면 무엇이 없는지 알려준다. */
sealed interface SettingsLookup<out T> {
    data class Ready<T>(val value: T) : SettingsLookup<T>
    data class Missing(val message: String) : SettingsLookup<Nothing>
}

data class IssueDraftSettings(val projectKey: String, val policy: WorkPolicy)

data class BranchSettings(
    val projectKey: String,
    val baseUrl: String,
    val email: String,
    val apiToken: String,
    val storyIssueTypeId: String,
    val inProgressStatusId: String,
)

data class CommitSettings(val projectKey: String)

/**
 * CLI `src/cli/index.ts`가 명령마다 하던 설정 확인(`getBranchConfig` 등)을 옮긴 자리다.
 * 안내 문구와 환경 변수 우선순위(`JIRA_EMAIL`, `JIRA_API_TOKEN`)를 CLI와 같게 유지한다.
 */
class JiraTaskSettings(
    private val repository: PolicyConfigRepository = PolicyConfigRepository(),
    private val credentials: CredentialStore = PasswordSafeCredentialStore(),
    private val environment: (String) -> String? = { System.getenv(it) },
) {
    fun forIssueDraft(): SettingsLookup<IssueDraftSettings> = withConfig { config ->
        val projectKey = config.projectKey
            ?: return@withConfig SettingsLookup.Missing(NO_PROJECT_KEY)
        SettingsLookup.Ready(IssueDraftSettings(projectKey, config.policy))
    }

    /** 이슈를 만들 때 필요한 이 사이트의 이슈 타입 ID들(설정 마법사가 저장해 둔 값). */
    fun issueTypeIds(): IssueTypeIds {
        val jira = try {
            repository.loadConfig().jira
        } catch (error: PolicyConfigurationError) {
            null
        }
        return IssueTypeIds(
            epic = jira?.issueTypes?.epic?.id,
            story = jira?.issueTypes?.story?.id,
            task = jira?.issueTypes?.task?.id,
        )
    }

    fun forCommit(): SettingsLookup<CommitSettings> = withConfig { config ->
        val projectKey = config.projectKey
            ?: return@withConfig SettingsLookup.Missing(NO_PROJECT_KEY)
        SettingsLookup.Ready(CommitSettings(projectKey))
    }

    fun forBranch(): SettingsLookup<BranchSettings> = withConfig { config ->
        val baseUrl = config.jira?.baseUrl
        val email = environment("JIRA_EMAIL") ?: config.jira?.email
        val storyIssueTypeId = config.jira?.issueTypes?.story?.id
        val inProgressStatusId = config.jira?.statuses?.story?.start?.id
        val apiToken = environment("JIRA_API_TOKEN")
            ?: if (baseUrl != null && email != null) {
                try {
                    credentials.get(baseUrl, email)
                } catch (error: CredentialStorageError) {
                    return@withConfig SettingsLookup.Missing(error.message ?: NO_TOKEN)
                }
            } else {
                null
            }

        val missing = listOfNotNull(
            "프로젝트 키(설정 마법사)".takeIf { config.projectKey == null },
            "Jira 주소(설정 마법사)".takeIf { baseUrl == null },
            "스토리 타입(설정 마법사)".takeIf { storyIssueTypeId == null },
            "진행 상태(설정 마법사)".takeIf { inProgressStatusId == null },
            "JIRA_EMAIL 또는 초기 설정 이메일".takeIf { email == null },
            "JIRA_API_TOKEN 또는 IDE 비밀번호 저장소의 토큰".takeIf { apiToken == null },
        )
        if (missing.isNotEmpty()) {
            return@withConfig SettingsLookup.Missing("브랜치 생성 설정이 없습니다: ${missing.joinToString(", ")}")
        }

        SettingsLookup.Ready(
            BranchSettings(
                projectKey = config.projectKey!!,
                baseUrl = baseUrl!!,
                email = email!!,
                apiToken = apiToken!!,
                storyIssueTypeId = storyIssueTypeId!!,
                inProgressStatusId = inProgressStatusId!!,
            ),
        )
    }

    private fun <T> withConfig(
        block: (io.github.gudwns1812.jiratask.domain.policy.JiraTaskConfig) -> SettingsLookup<T>,
    ): SettingsLookup<T> {
        val config = try {
            repository.loadConfig()
        } catch (error: PolicyConfigurationError) {
            return SettingsLookup.Missing(error.message ?: "정책 설정을 읽을 수 없습니다.")
        }
        return block(config)
    }

    companion object {
        const val NO_PROJECT_KEY: String =
            "Jira 프로젝트 키가 없습니다. 설정 마법사를 실행하거나 Settings | Tools | Jira Task에서 지정하세요."
        const val NO_TOKEN: String = "Jira API 토큰을 읽지 못했습니다."
    }
}
