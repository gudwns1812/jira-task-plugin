package io.github.gudwns1812.jiratask.ui.toolwindow

import io.github.gudwns1812.jiratask.application.SettingsLoad
import io.github.gudwns1812.jiratask.application.SettingsSnapshot
import io.github.gudwns1812.jiratask.domain.jira.SprintTask
import io.github.gudwns1812.jiratask.ui.task.SprintTaskLoad

data class StatusRow(
    val name: String,
    val value: String,
    val isPlaceholder: Boolean,
    val kind: Kind,
) {
    enum class Kind { TEXT, LINK, CODE }
}

data class StatusProblem(val kind: Kind, val message: String) {
    enum class Kind { NEEDS_SETUP, BROKEN }
}

data class JiraTaskStatus(
    val projectKey: String?,
    val baseUrl: String?,
    val gitLabBaseUrl: String?,
    val projectName: String?,
    val storyIssueType: String?,
    val taskNumber: String?,
    val branch: String?,
    val problem: StatusProblem?,
) {
    val isConfigured: Boolean get() = problem == null && !projectKey.isNullOrEmpty()

    fun currentIssueKey(): String? =
        if (projectKey.isNullOrBlank() || taskNumber.isNullOrBlank()) null else "$projectKey-$taskNumber"

    fun issueUrl(issueKey: String): String? =
        baseUrl?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let { "$it/browse/$issueKey" }

    fun settingsRows(): List<StatusRow> = listOf(
        row("프로젝트 키", projectKey, NOT_CONFIGURED, StatusRow.Kind.CODE),
        row("Jira 주소", baseUrl, NOT_CONFIGURED, StatusRow.Kind.LINK),
        row("GitLab 주소", gitLabBaseUrl, NOT_CONFIGURED, StatusRow.Kind.LINK),
        row("프로젝트", projectName, NOT_CONFIGURED, StatusRow.Kind.TEXT),
        row("스토리 유형", storyIssueType, NOT_CONFIGURED, StatusRow.Kind.TEXT),
    )

    fun rows(): List<Pair<String, String>> = settingsRows().map { it.name to it.value }

    private fun row(name: String, value: String?, placeholder: String, kind: StatusRow.Kind): StatusRow =
        if (value.isNullOrBlank()) {
            StatusRow(name, placeholder, isPlaceholder = true, kind = kind)
        } else {
            StatusRow(name, value, isPlaceholder = false, kind = kind)
        }

    companion object {
        const val NOT_CONFIGURED: String = "설정되지 않음"
        const val NO_TASK: String = "작업 없음"
        const val NO_BRANCH: String = "알 수 없음"
        const val NEEDS_SETUP: String = "Jira 설정이 없습니다. 설정 마법사를 실행하세요."

        val EMPTY: JiraTaskStatus = JiraTaskStatus(null, null, null, null, null, null, null, null)

        val ROW_NAMES: List<String> = EMPTY.rows().map { it.first }
    }
}

data class JiraTaskBoard(
    val status: JiraTaskStatus,
    val currentTask: SprintTask? = null,
    val sprint: SprintTaskLoad? = null,
) {
    fun currentTaskTitle(): String = currentTask?.summary?.takeIf { it.isNotBlank() }
        ?: status.currentIssueKey()?.let { NO_SUMMARY }
        ?: JiraTaskStatus.NO_TASK

    companion object {
        const val NO_SUMMARY: String = "제목을 읽지 못했습니다."

        val EMPTY: JiraTaskBoard = JiraTaskBoard(JiraTaskStatus.EMPTY)
    }
}

fun JiraTaskStatus.Companion.of(load: SettingsLoad, taskNumber: String?, branch: String?): JiraTaskStatus =
    when (load) {
        is SettingsLoad.Broken -> JiraTaskStatus(
            projectKey = null,
            baseUrl = null,
            gitLabBaseUrl = null,
            projectName = null,
            storyIssueType = null,
            taskNumber = taskNumber,
            branch = branch,
            problem = StatusProblem(StatusProblem.Kind.BROKEN, load.message),
        )
        is SettingsLoad.Loaded -> load.snapshot.toStatus(taskNumber, branch)
    }

private fun SettingsSnapshot.toStatus(taskNumber: String?, branch: String?): JiraTaskStatus = JiraTaskStatus(
    projectKey = projectKey,
    baseUrl = baseUrl,
    gitLabBaseUrl = gitLabBaseUrl,
    projectName = projectName,
    storyIssueType = storyIssueType,
    taskNumber = taskNumber,
    branch = branch,
    problem = if (projectKey.isEmpty()) {
        StatusProblem(StatusProblem.Kind.NEEDS_SETUP, JiraTaskStatus.NEEDS_SETUP)
    } else {
        null
    },
)
