package io.github.gudwns1812.jiratask.ui

import io.github.gudwns1812.jiratask.application.CredentialStore
import io.github.gudwns1812.jiratask.domain.policy.AiProvider
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

/**
 * AI 초안에 필요한 값.
 *
 * API 키가 없다 — 사용자가 이미 로그인해 둔 CLI를 그대로 쓰기 때문이다.
 * 플러그인이 자격 증명을 갖지 않는 것이 이 기능을 구독 요금제로 쓸 수 있는 이유다.
 */
data class AiDraftSettings(
    /** 어느 CLI에게 맡길지. 모델과 실행 파일 경로는 이 provider에 저장된 것이다. */
    val provider: AiProvider,
    val model: String?,
    val executablePath: String?,
    /** 커밋 초안에 변경 내용(diff)까지 보낼지. 커밋 초안에서만 본다. */
    val sendDiff: Boolean = true,
)

/** 토큰을 어디서 가져왔는가. 거부당했을 때 **어디를 고쳐야 하는지** 말해 주려고 들고 다닌다. */
enum class GitLabTokenSource {
    SETTINGS,
    ENVIRONMENT,
    ;

    fun describe(): String = when (this) {
        SETTINGS -> "설정 화면에 저장된 토큰"
        ENVIRONMENT -> "GITLAB_TOKEN 환경 변수의 토큰"
    }
}

/** MR을 만드는 데 필요한 값. Jira 주소는 본문에 이슈 링크를 적는 데 쓴다. */
data class MergeRequestSettings(
    val gitLabBaseUrl: String,
    val gitLabToken: String,
    val jiraBaseUrl: String?,
    val tokenSource: GitLabTokenSource = GitLabTokenSource.SETTINGS,
    /** MR 창을 열 때 리뷰어로 미리 넣어 둘 GitLab 아이디들. */
    val defaultReviewers: List<String> = emptyList(),
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
    /**
     * GitLab 주소에 저장해 둔 토큰. 기본 구현은 IDE 비밀번호 저장소다.
     *
     * 저장소 타입이 아니라 함수로 받는 이유는 [environment]와 같다 — [GitLabTokenStore]는
     * `PasswordSafe`를 물고 있어 IDE 없이는 값을 돌려주게 흉내낼 수 없고, 그러면 토큰 우선순위를
     * 테스트할 방법이 사라진다.
     */
    private val gitLabToken: (String) -> String? = GitLabTokenStore()::get,
    private val environment: (String) -> String? = { System.getenv(it) },
) {
    fun forCommit(): SettingsLookup<CommitSettings> = withConfig { config ->
        val projectKey = config.projectKey
            ?: return@withConfig SettingsLookup.Missing(NO_PROJECT_KEY)
        SettingsLookup.Ready(CommitSettings(projectKey))
    }

    /**
     * MR 생성에 필요한 설정. GitLab 주소와 토큰이 없으면 무엇이 없는지 알려준다.
     *
     * **설정 화면의 토큰이 환경 변수를 이긴다.** 반대로 두면 사용자가 화면에서 고칠 수 없는 값이
     * 항상 이겨서, 올바른 토큰을 넣어도 계속 거부당하고 그 이유가 화면 어디에도 보이지 않는다.
     * 환경 변수는 화면에 아무것도 저장하지 않은 경우(CI, 스크립트)를 위한 뒷받침으로만 남긴다.
     */
    fun forMergeRequest(): SettingsLookup<MergeRequestSettings> = withConfig { config ->
        val baseUrl = config.gitlab?.baseUrl
            ?: return@withConfig SettingsLookup.Missing(NO_GITLAB)

        val saved = try {
            gitLabToken(baseUrl)
        } catch (error: CredentialStorageError) {
            return@withConfig SettingsLookup.Missing(error.message ?: NO_GITLAB_TOKEN)
        }?.trim()?.ifEmpty { null }

        // 빈 문자열도 없는 것으로 친다. `GITLAB_TOKEN=`은 non-null이라 그냥 두면 빈 헤더를 보내 401이 된다.
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

    /** AI 초안 설정. 꺼져 있으면 왜 쓸 수 없는지 알려준다. */
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
        const val AI_DISABLED: String =
            "AI 초안이 꺼져 있습니다. Settings | Tools | Jira Task 에서 켜세요."
        const val NO_GITLAB_TOKEN: String =
            "GitLab 액세스 토큰이 없습니다. GITLAB_TOKEN 환경 변수 또는 설정 화면에서 입력하세요."
    }
}
