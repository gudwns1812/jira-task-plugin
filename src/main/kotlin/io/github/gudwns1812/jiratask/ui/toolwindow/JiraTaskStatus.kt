package io.github.gudwns1812.jiratask.ui.toolwindow

import io.github.gudwns1812.jiratask.application.SettingsLoad
import io.github.gudwns1812.jiratask.application.SettingsSnapshot
import io.github.gudwns1812.jiratask.domain.jira.SprintTask
import io.github.gudwns1812.jiratask.ui.task.SprintTaskLoad

/**
 * 상태 한 줄. [isPlaceholder]가 true면 [value]는 실제 값이 아니라 "설정되지 않음" 같은 안내 문구이므로
 * 회색으로 그리고, [kind]는 그 값을 어떻게 보여줄지(일반 문구 / 링크 / 고정폭)를 말한다.
 */
data class StatusRow(
    val name: String,
    val value: String,
    val isPlaceholder: Boolean,
    val kind: Kind,
) {
    enum class Kind { TEXT, LINK, CODE }
}

/**
 * 상태 행을 보여줄 수 없는 이유. 종류에 따라 화면이 달라진다 —
 * [Kind.NEEDS_SETUP]은 마법사를 안내하는 빈 상태, [Kind.BROKEN]은 오류와 설정 열기 안내.
 */
data class StatusProblem(val kind: Kind, val message: String) {
    enum class Kind { NEEDS_SETUP, BROKEN }
}

/**
 * 툴 윈도우가 보여줄 현재 상태. 이메일과 API 토큰은 담지 않는다.
 *
 * 값이 두 종류로 갈린다. **지금 하는 일**(작업 번호, 브랜치)은 코딩하는 동안 바뀌므로 위에 크게 두고,
 * **설정 값**(프로젝트 키, 주소, 유형)은 한 번 정하면 볼 일이 없으므로 접어 둔다.
 */
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
    /** 명령을 실행할 수 있는 상태인지 — 프로젝트 키가 있어야 한다. */
    val isConfigured: Boolean get() = problem == null && !projectKey.isNullOrEmpty()

    /** 현재 작업의 Jira 이슈 키. 작업 번호와 프로젝트 키가 모두 있어야 만들 수 있다. */
    fun currentIssueKey(): String? =
        if (projectKey.isNullOrBlank() || taskNumber.isNullOrBlank()) null else "$projectKey-$taskNumber"

    /** 이 이슈를 Jira에서 여는 주소. */
    fun issueUrl(issueKey: String): String? =
        baseUrl?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let { "$it/browse/$issueKey" }

    /** 접어 두는 설정 요약. 확인할 일은 있지만 늘 보고 있을 값은 아니다. */
    fun settingsRows(): List<StatusRow> = listOf(
        row("프로젝트 키", projectKey, NOT_CONFIGURED, StatusRow.Kind.CODE),
        row("Jira 주소", baseUrl, NOT_CONFIGURED, StatusRow.Kind.LINK),
        row("GitLab 주소", gitLabBaseUrl, NOT_CONFIGURED, StatusRow.Kind.LINK),
        row("프로젝트", projectName, NOT_CONFIGURED, StatusRow.Kind.TEXT),
        row("스토리 유형", storyIssueType, NOT_CONFIGURED, StatusRow.Kind.TEXT),
    )

    /** 이름과 표시 문구만 필요한 곳(로그·간단한 검증)에서 쓰는 축약형. */
    fun rows(): List<Pair<String, String>> = settingsRows().map { it.name to it.value }

    // 종류는 항목의 성격이므로 값이 있든 없든 그대로 둔다 — 값이 없다는 사실은 isPlaceholder가 알려준다.
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

        /** 아직 아무것도 읽지 못한 상태. */
        val EMPTY: JiraTaskStatus = JiraTaskStatus(null, null, null, null, null, null, null, null)

        /** 툴 윈도우가 접어 두는 설정 항목 이름. [rows]의 키와 항상 같다. */
        val ROW_NAMES: List<String> = EMPTY.rows().map { it.first }
    }
}

/**
 * 툴 윈도우가 한 번에 그리는 것 전부.
 *
 * 설정만이 아니라 **지금 하는 일**을 담는다 — 현재 작업의 제목·상태와 스프린트 목록까지.
 * 번호만 보여 주던 시절에는 그 번호가 무슨 작업인지 Jira를 열어야 알 수 있었다.
 */
data class JiraTaskBoard(
    val status: JiraTaskStatus,
    /** 현재 작업의 Jira 상세. 아직 못 읽었거나 없는 작업이면 null. */
    val currentTask: SprintTask? = null,
    /** 현재 스프린트 목록. 아직 읽지 않았으면 null. */
    val sprint: SprintTaskLoad? = null,
) {
    /** 현재 작업을 한 줄로. Jira에서 제목을 못 읽었으면 키만이라도 보여 준다. */
    fun currentTaskTitle(): String = currentTask?.summary?.takeIf { it.isNotBlank() }
        ?: status.currentIssueKey()?.let { NO_SUMMARY }
        ?: JiraTaskStatus.NO_TASK

    companion object {
        const val NO_SUMMARY: String = "제목을 읽지 못했습니다."

        val EMPTY: JiraTaskBoard = JiraTaskBoard(JiraTaskStatus.EMPTY)
    }
}

/** 설정 읽기 결과와 그때 읽은 작업 번호·브랜치로 현재 상태를 만든다. */
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
