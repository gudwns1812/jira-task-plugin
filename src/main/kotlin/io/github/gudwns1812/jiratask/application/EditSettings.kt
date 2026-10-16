package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.mr.GitLabBaseUrl
import io.github.gudwns1812.jiratask.domain.policy.AiCliSettings
import io.github.gudwns1812.jiratask.domain.policy.AiProvider
import io.github.gudwns1812.jiratask.domain.policy.JiraTaskConfig
import io.github.gudwns1812.jiratask.domain.policy.PolicyConfigurationError
import io.github.gudwns1812.jiratask.domain.policy.default

data class SettingsSnapshot(
    val projectKey: String,
    val baseUrl: String,
    val projectName: String,
    val storyIssueType: String,
    val taskIssueType: String,
    val epicIssueType: String,
    val gitLabBaseUrl: String,
    val gitLabReviewers: String = "",
    val aiEnabled: Boolean = false,
    val aiProvider: String = AiProvider.CLAUDE.id,
    val aiSendDiff: Boolean = true,
    val aiClis: Map<String, AiCliSnapshot> = emptyMap(),
) {

    fun aiCli(providerId: String = aiProvider): AiCliSnapshot = aiClis[providerId] ?: AiCliSnapshot()

    val aiModel: String get() = aiCli().model

    val aiExecutablePath: String get() = aiCli().executablePath

    companion object
}

data class AiCliSnapshot(val model: String = "", val executablePath: String = "")

fun SettingsSnapshot.Companion.from(config: JiraTaskConfig): SettingsSnapshot = SettingsSnapshot(
    projectKey = config.projectKey.orEmpty(),
    baseUrl = config.jira?.baseUrl.orEmpty(),
    projectName = config.jira?.project?.name.orEmpty(),
    storyIssueType = config.jira?.issueTypes?.story?.name.orEmpty(),
    taskIssueType = config.jira?.issueTypes?.task?.name.orEmpty(),
    epicIssueType = config.jira?.issueTypes?.epic?.name.orEmpty(),
    gitLabBaseUrl = config.gitlab?.baseUrl.orEmpty(),
    gitLabReviewers = config.gitlab?.defaultReviewers.orEmpty().joinToString(", "),
    aiEnabled = config.ai?.enabled ?: false,
    aiProvider = (config.ai?.provider ?: AiProvider.CLAUDE).id,
    aiSendDiff = config.ai?.sendDiff ?: true,
    aiClis = AiProvider.entries.associate { it.id to (config.ai?.cliSettings(it) ?: AiCliSettings()).toSnapshot() },
)

private fun AiCliSettings.toSnapshot(): AiCliSnapshot =
    AiCliSnapshot(model = model.orEmpty(), executablePath = executablePath.orEmpty())

fun SettingsSnapshot.Companion.empty(): SettingsSnapshot = SettingsSnapshot.from(JiraTaskConfig.default())

data class SettingsEdit(
    val projectKey: String,
    val baseUrl: String,
    val gitLabBaseUrl: String = "",
    val gitLabReviewers: String = "",
    val aiEnabled: Boolean? = null,
    val aiProvider: AiProvider? = null,
    val aiModel: String = "",
    val aiExecutablePath: String = "",
    val aiSendDiff: Boolean? = null,
) {

    fun projectKeyOrNull(): String? = projectKey.trim().ifEmpty { null }

    fun baseUrlOrNull(): String? = baseUrl.trim().ifEmpty { null }

    fun gitLabBaseUrlOrNull(): String? = GitLabBaseUrl.normalize(gitLabBaseUrl).ifEmpty { null }

    fun gitLabReviewersOrNull(): List<String>? = gitLabReviewers.trim().ifEmpty { null }
        ?.split(',', ' ')
        ?.map { it.trim().removePrefix("@") }
        ?.filter { it.isNotEmpty() }
        ?.distinct()

    fun aiModelOrNull(): String? = aiModel.trim().ifEmpty { null }

    fun aiExecutablePathOrNull(): String? = aiExecutablePath.trim().ifEmpty { null }

    fun toConfigUpdate(): Map<String, Any?> {
        val update = mutableMapOf<String, Any?>()
        projectKeyOrNull()?.let { update["projectKey"] = it }
        baseUrlOrNull()?.let { update["jira"] = mapOf("baseUrl" to it) }
        val gitlab = mutableMapOf<String, Any?>()
        gitLabBaseUrlOrNull()?.let { gitlab["baseUrl"] = it }
        gitLabReviewersOrNull()?.let { gitlab["defaultReviewers"] = it }
        if (gitlab.isNotEmpty()) {
            update["gitlab"] = gitlab
        }

        val ai = mutableMapOf<String, Any?>()
        aiEnabled?.let { ai["enabled"] = it }
        aiSendDiff?.let { ai["sendDiff"] = it }
        aiProvider?.let { provider ->
            ai["provider"] = provider.id

            val cli = mutableMapOf<String, Any?>()
            aiModelOrNull()?.let { cli["model"] = it }
            aiExecutablePathOrNull()?.let { cli["executablePath"] = it }
            if (cli.isNotEmpty()) {
                ai[provider.id] = cli
            }
        }
        if (ai.isNotEmpty()) {
            update["ai"] = ai
        }

        return update
    }

    fun changes(snapshot: SettingsSnapshot): Boolean {
        val projectKeyChanged = projectKeyOrNull()?.let { it != snapshot.projectKey } ?: false
        val baseUrlChanged = baseUrlOrNull()?.let { it != snapshot.baseUrl } ?: false
        val gitLabChanged = gitLabBaseUrlOrNull()?.let { it != snapshot.gitLabBaseUrl } ?: false
        val reviewersChanged = gitLabReviewersOrNull()
            ?.let { it.joinToString(", ") != snapshot.gitLabReviewers } ?: false
        val aiEnabledChanged = aiEnabled?.let { it != snapshot.aiEnabled } ?: false
        val aiSendDiffChanged = aiSendDiff?.let { it != snapshot.aiSendDiff } ?: false
        val aiProviderChanged = aiProvider?.let { it.id != snapshot.aiProvider } ?: false

        val savedCli = aiProvider?.let { snapshot.aiCli(it.id) }
        val aiModelChanged = savedCli?.let { saved -> aiModelOrNull()?.let { it != saved.model } } ?: false
        val aiPathChanged =
            savedCli?.let { saved -> aiExecutablePathOrNull()?.let { it != saved.executablePath } } ?: false

        return projectKeyChanged || baseUrlChanged || gitLabChanged || reviewersChanged ||
            aiEnabledChanged || aiProviderChanged || aiModelChanged || aiPathChanged || aiSendDiffChanged
    }
}

sealed interface SettingsLoad {
    data class Loaded(val snapshot: SettingsSnapshot) : SettingsLoad
    data class Broken(val message: String) : SettingsLoad
}

class EditSettingsUseCase(
    private val loadConfig: () -> JiraTaskConfig,
    private val saveConfig: (Map<String, Any?>) -> Unit,
) {
    fun load(): SettingsLoad = try {
        SettingsLoad.Loaded(SettingsSnapshot.from(loadConfig()))
    } catch (error: PolicyConfigurationError) {
        SettingsLoad.Broken(error.message ?: "정책 설정을 읽을 수 없습니다.")
    }

    fun save(edit: SettingsEdit): SettingsSnapshot {
        val update = edit.toConfigUpdate()
        if (update.isNotEmpty()) {
            saveConfig(update)
        }
        return SettingsSnapshot.from(loadConfig())
    }
}
