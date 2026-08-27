package io.github.gudwns1812.jiratask.ui

import io.github.gudwns1812.jiratask.application.CredentialStore
import io.github.gudwns1812.jiratask.domain.policy.PolicyConfigurationError
import io.github.gudwns1812.jiratask.infrastructure.CredentialStorageError
import io.github.gudwns1812.jiratask.infrastructure.GitLabTokenStore
import io.github.gudwns1812.jiratask.infrastructure.PasswordSafeCredentialStore
import io.github.gudwns1812.jiratask.infrastructure.PolicyConfigRepository

/** 저장된 설정에서 명령 하나를 실행할 수 있는지 본 결과. 부족하면 무엇이 없는지 알려준다. */
sealed interface SettingsLookup<out T> {
    data class Ready<T>(val value: T) : SettingsLookup<T>
    data class Missing(val message: String) : SettingsLookup<Nothing>
}

data class CommitSettings(val projectKey: String)

/** MR을 만드는 데 필요한 값. Jira 주소는 본문에 이슈 링크를 적는 데 쓴다. */
data class MergeRequestSettings(
    val gitLabBaseUrl: String,
    val gitLabToken: String,
    val jiraBaseUrl: String?,
)

/**
 * 작업 이슈를 다루는 데 필요한 값.
 *
 * 어느 상태가 "진행 중"인지는 담지 않는다. Jira의 상태 카테고리가 이미 답이라
 * [io.github.gudwns1812.jiratask.application.AdvanceTaskUseCase]가 그 이슈의 워크플로에서 직접 고른다.
 */
data class TaskTransitionSettings(
    val projectKey: String,
    val baseUrl: String,
    val email: String,
    val apiToken: String,
    val taskIssueTypeId: String,
)

/**
 * CLI `src/cli/index.ts`가 명령마다 하던 설정 확인(`getBranchConfig` 등)을 옮긴 자리다.
 * 안내 문구와 환경 변수 우선순위(`JIRA_EMAIL`, `JIRA_API_TOKEN`)를 CLI와 같게 유지한다.
 */
class JiraTaskSettings(
    private val repository: PolicyConfigRepository = PolicyConfigRepository(),
    private val credentials: CredentialStore = PasswordSafeCredentialStore(),
    private val gitLabTokens: GitLabTokenStore = GitLabTokenStore(),
    private val environment: (String) -> String? = { System.getenv(it) },
) {
    fun forCommit(): SettingsLookup<CommitSettings> = withConfig { config ->
        val projectKey = config.projectKey
            ?: return@withConfig SettingsLookup.Missing(NO_PROJECT_KEY)
        SettingsLookup.Ready(CommitSettings(projectKey))
    }

    /** MR 생성에 필요한 설정. GitLab 주소와 토큰이 없으면 무엇이 없는지 알려준다. */
    fun forMergeRequest(): SettingsLookup<MergeRequestSettings> = withConfig { config ->
        val baseUrl = config.gitlab?.baseUrl
            ?: return@withConfig SettingsLookup.Missing(NO_GITLAB)

        val token = environment("GITLAB_TOKEN")
            ?: try {
                gitLabTokens.get(baseUrl)
            } catch (error: CredentialStorageError) {
                return@withConfig SettingsLookup.Missing(error.message ?: NO_GITLAB_TOKEN)
            }
            ?: return@withConfig SettingsLookup.Missing(NO_GITLAB_TOKEN)

        SettingsLookup.Ready(MergeRequestSettings(baseUrl, token, config.jira?.baseUrl))
    }

    /** 작업을 시작할 때의 상태 전환에 필요한 설정. 하나라도 없으면 무엇이 없는지 알려준다. */
    fun forTaskTransition(): SettingsLookup<TaskTransitionSettings> = withConfig { config ->
        val baseUrl = config.jira?.baseUrl
        val email = environment("JIRA_EMAIL") ?: config.jira?.email
        val taskIssueTypeId = config.jira?.issueTypes?.task?.id
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
            "작업 타입(설정 마법사)".takeIf { taskIssueTypeId == null },
            "JIRA_EMAIL 또는 초기 설정 이메일".takeIf { email == null },
            "JIRA_API_TOKEN 또는 IDE 비밀번호 저장소의 토큰".takeIf { apiToken == null },
        )
        if (missing.isNotEmpty()) {
            return@withConfig SettingsLookup.Missing("작업 상태 전환 설정이 없습니다: ${missing.joinToString(", ")}")
        }

        SettingsLookup.Ready(
            TaskTransitionSettings(
                projectKey = config.projectKey!!,
                baseUrl = baseUrl!!,
                email = email!!,
                apiToken = apiToken!!,
                taskIssueTypeId = taskIssueTypeId!!,
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
        const val NO_GITLAB: String =
            "GitLab 주소가 설정되지 않았습니다. Settings | Tools | Jira Task 에서 입력하세요."
        const val NO_GITLAB_TOKEN: String =
            "GitLab 액세스 토큰이 없습니다. GITLAB_TOKEN 환경 변수 또는 설정 화면에서 입력하세요."
    }
}
