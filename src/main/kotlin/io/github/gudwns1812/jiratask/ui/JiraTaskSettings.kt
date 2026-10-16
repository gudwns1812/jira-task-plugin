package io.github.gudwns1812.jiratask.ui

import io.github.gudwns1812.jiratask.application.CredentialStore
import io.github.gudwns1812.jiratask.domain.policy.AiProvider
import io.github.gudwns1812.jiratask.domain.policy.PolicyConfigurationError
import io.github.gudwns1812.jiratask.infrastructure.CredentialStorageError
import io.github.gudwns1812.jiratask.infrastructure.GitLabTokenStore
import io.github.gudwns1812.jiratask.infrastructure.PasswordSafeCredentialStore
import io.github.gudwns1812.jiratask.infrastructure.PolicyConfigRepository

sealed interface SettingsLookup<out T> {
    data class Ready<T>(val value: T) : SettingsLookup<T>
    data class Missing(val message: String) : SettingsLookup<Nothing>
}

data class CommitSettings(val projectKey: String)

data class AiDraftSettings(
    val provider: AiProvider,
    val model: String?,
    val executablePath: String?,
    val sendDiff: Boolean = true,
)

enum class GitLabTokenSource {
    SETTINGS,
    ENVIRONMENT,
    ;

    fun describe(): String = when (this) {
        SETTINGS -> "설정 화면에 저장된 토큰"
        ENVIRONMENT -> "GITLAB_TOKEN 환경 변수의 토큰"
    }
}

data class MergeRequestSettings(
    val gitLabBaseUrl: String,
    val gitLabToken: String,
    val jiraBaseUrl: String?,
    val tokenSource: GitLabTokenSource = GitLabTokenSource.SETTINGS,
    val defaultReviewers: List<String> = emptyList(),
)

data class TaskTransitionSettings(
    val projectKey: String,
    val baseUrl: String,
    val email: String,
    val apiToken: String,
    val taskIssueTypeId: String,
)

class JiraTaskSettings(
    private val repository: PolicyConfigRepository = PolicyConfigRepository(),
    private val credentials: CredentialStore = PasswordSafeCredentialStore(),
    private val gitLabToken: (String) -> String? = GitLabTokenStore()::get,
    private val environment: (String) -> String? = { System.getenv(it) },
) {
    fun forCommit(): SettingsLookup<CommitSettings> = withConfig { config ->
        val projectKey = config.projectKey
            ?: return@withConfig SettingsLookup.Missing(NO_PROJECT_KEY)
        SettingsLookup.Ready(CommitSettings(projectKey))
    }

    fun forMergeRequest(): SettingsLookup<MergeRequestSettings> = withConfig { config ->
        val baseUrl = config.gitlab?.baseUrl
            ?: return@withConfig SettingsLookup.Missing(NO_GITLAB)

        val saved = try {
            gitLabToken(baseUrl)
        } catch (error: CredentialStorageError) {
            return@withConfig SettingsLookup.Missing(error.message ?: NO_GITLAB_TOKEN)
        }?.trim()?.ifEmpty { null }

        val fromEnvironment = environment("GITLAB_TOKEN")?.trim()?.ifEmpty { null }

        val (token, source) = when {
            saved != null -> saved to GitLabTokenSource.SETTINGS
            fromEnvironment != null -> fromEnvironment to GitLabTokenSource.ENVIRONMENT
            else -> return@withConfig SettingsLookup.Missing(NO_GITLAB_TOKEN)
        }

        SettingsLookup.Ready(
            MergeRequestSettings(
                gitLabBaseUrl = baseUrl,
                gitLabToken = token,
                jiraBaseUrl = config.jira?.baseUrl,
                tokenSource = source,
                defaultReviewers = config.gitlab?.defaultReviewers.orEmpty(),
            ),
        )
    }

    fun forAiDraft(): SettingsLookup<AiDraftSettings> = withConfig { config ->
        val ai = config.ai?.takeIf { it.enabled }
            ?: return@withConfig SettingsLookup.Missing(AI_DISABLED)

        SettingsLookup.Ready(
            AiDraftSettings(
                provider = ai.provider,
                model = ai.model,
                executablePath = ai.executablePath,
                sendDiff = ai.sendDiff,
            ),
        )
    }

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
        const val AI_DISABLED: String =
            "AI 초안이 꺼져 있습니다. Settings | Tools | Jira Task 에서 켜세요."
        const val NO_GITLAB_TOKEN: String =
            "GitLab 액세스 토큰이 없습니다. GITLAB_TOKEN 환경 변수 또는 설정 화면에서 입력하세요."
    }
}
