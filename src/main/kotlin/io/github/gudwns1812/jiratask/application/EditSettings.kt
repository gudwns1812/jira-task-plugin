package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.mr.GitLabBaseUrl
import io.github.gudwns1812.jiratask.domain.policy.JiraTaskConfig
import io.github.gudwns1812.jiratask.domain.policy.PolicyConfigurationError
import io.github.gudwns1812.jiratask.domain.policy.default

/**
 * 설정 화면에 보여줄 값들. CLI `config show`와 같은 정보를 담되, 이메일과 API 토큰은
 * 애초에 필드로 두지 않아 화면에 노출될 수 없게 한다(CLI의 `redactJiraSettings`와 같은 의도).
 */
data class SettingsSnapshot(
    val projectKey: String,
    val baseUrl: String,
    val projectName: String,
    val storyIssueType: String,
    val taskIssueType: String,
    val epicIssueType: String,
    val gitLabBaseUrl: String,
    val aiEnabled: Boolean = false,
    val aiModel: String = "",
    val aiExecutablePath: String = "",
) {
    companion object
}

fun SettingsSnapshot.Companion.from(config: JiraTaskConfig): SettingsSnapshot = SettingsSnapshot(
    projectKey = config.projectKey.orEmpty(),
    baseUrl = config.jira?.baseUrl.orEmpty(),
    projectName = config.jira?.project?.name.orEmpty(),
    storyIssueType = config.jira?.issueTypes?.story?.name.orEmpty(),
    taskIssueType = config.jira?.issueTypes?.task?.name.orEmpty(),
    epicIssueType = config.jira?.issueTypes?.epic?.name.orEmpty(),
    gitLabBaseUrl = config.gitlab?.baseUrl.orEmpty(),
    aiEnabled = config.ai?.enabled ?: false,
    aiModel = config.ai?.model.orEmpty(),
    aiExecutablePath = config.ai?.executablePath.orEmpty(),
)

/** 저장된 설정이 없거나 아직 읽지 못한 상태를 나타내는 빈 스냅샷. */
fun SettingsSnapshot.Companion.empty(): SettingsSnapshot = SettingsSnapshot.from(JiraTaskConfig.default())

/**
 * 설정 화면에서 사용자가 직접 편집할 수 있는 값. 공백만 입력한 필드는 "변경하지 않음"으로
 * 본다 — CLI `config set`에서 해당 옵션을 생략한 것과 같다(얕은 병합이라 값 삭제는 지원하지 않음).
 */
data class SettingsEdit(
    val projectKey: String,
    val baseUrl: String,
    val gitLabBaseUrl: String = "",
    /**
     * null은 "이 화면이 AI를 다루지 않았다"는 뜻이다.
     *
     * 체크박스에는 "비워 둠"이 없어서 다른 칸과 같은 규칙을 쓸 수 없다. null을 두지 않으면
     * [toConfigUpdate]가 언제나 값을 내보내게 되고, 아무것도 안 고쳤는데 저장이 일어난다.
     */
    val aiEnabled: Boolean? = null,
    val aiModel: String = "",
    val aiExecutablePath: String = "",
) {

    fun projectKeyOrNull(): String? = projectKey.trim().ifEmpty { null }

    fun baseUrlOrNull(): String? = baseUrl.trim().ifEmpty { null }

    /** 저장하는 주소도 토큰 열쇠와 같은 모양으로 맞춘다. 그래야 나중에 토큰을 다시 찾을 수 있다. */
    fun gitLabBaseUrlOrNull(): String? = GitLabBaseUrl.normalize(gitLabBaseUrl).ifEmpty { null }

    fun aiModelOrNull(): String? = aiModel.trim().ifEmpty { null }

    fun aiExecutablePathOrNull(): String? = aiExecutablePath.trim().ifEmpty { null }

    fun toConfigUpdate(): Map<String, Any?> {
        val update = mutableMapOf<String, Any?>()
        projectKeyOrNull()?.let { update["projectKey"] = it }
        baseUrlOrNull()?.let { update["jira"] = mapOf("baseUrl" to it) }
        gitLabBaseUrlOrNull()?.let { update["gitlab"] = mapOf("baseUrl" to it) }

        // 한 겹 깊이로 합쳐지므로(PolicyConfig.mergeWith) 이번에 채운 값만 실어도 나머지가 남는다.
        val ai = mutableMapOf<String, Any?>()
        aiEnabled?.let { ai["enabled"] = it }
        aiModelOrNull()?.let { ai["model"] = it }
        aiExecutablePathOrNull()?.let { ai["executablePath"] = it }
        if (ai.isNotEmpty()) {
            update["ai"] = ai
        }

        return update
    }

    /** [snapshot]과 달라지는 값이 하나라도 있는지 — Settings 화면의 Apply 활성화 조건이다. */
    fun changes(snapshot: SettingsSnapshot): Boolean {
        val projectKeyChanged = projectKeyOrNull()?.let { it != snapshot.projectKey } ?: false
        val baseUrlChanged = baseUrlOrNull()?.let { it != snapshot.baseUrl } ?: false
        val gitLabChanged = gitLabBaseUrlOrNull()?.let { it != snapshot.gitLabBaseUrl } ?: false
        val aiEnabledChanged = aiEnabled?.let { it != snapshot.aiEnabled } ?: false
        val aiModelChanged = aiModelOrNull()?.let { it != snapshot.aiModel } ?: false
        val aiPathChanged = aiExecutablePathOrNull()?.let { it != snapshot.aiExecutablePath } ?: false
        return projectKeyChanged || baseUrlChanged || gitLabChanged ||
            aiEnabledChanged || aiModelChanged || aiPathChanged
    }
}

/** 저장된 설정을 읽은 결과. 손상된 설정도 화면을 못 열게 하지 않고 안내 문구로 보여주기 위해 구분한다. */
sealed interface SettingsLoad {
    data class Loaded(val snapshot: SettingsSnapshot) : SettingsLoad
    data class Broken(val message: String) : SettingsLoad
}

/**
 * CLI의 `config show`(읽기)와 `config set`(프로젝트 키·Jira 주소 저장)에 대응하는 유스케이스.
 * 협력자는 설정 저장소의 읽기·쓰기 함수뿐이므로 UI 프레임워크에 의존하지 않는다.
 */
class EditSettingsUseCase(
    private val loadConfig: () -> JiraTaskConfig,
    private val saveConfig: (Map<String, Any?>) -> Unit,
) {
    fun load(): SettingsLoad = try {
        SettingsLoad.Loaded(SettingsSnapshot.from(loadConfig()))
    } catch (error: PolicyConfigurationError) {
        SettingsLoad.Broken(error.message ?: "정책 설정을 읽을 수 없습니다.")
    }

    /**
     * 편집한 값을 검증한 뒤 저장하고, 저장 후 스냅샷을 돌려준다. 값이 유효하지 않으면
     * [PolicyConfigurationError]를 던지며 이때 저장소는 전혀 바뀌지 않는다.
     */
    fun save(edit: SettingsEdit): SettingsSnapshot {
        val update = edit.toConfigUpdate()
        if (update.isNotEmpty()) {
            saveConfig(update)
        }
        return SettingsSnapshot.from(loadConfig())
    }
}
