package io.github.gudwns1812.jiratask.ui.toolwindow

import io.github.gudwns1812.jiratask.application.SettingsLoad
import io.github.gudwns1812.jiratask.application.SettingsSnapshot

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
 * 툴 윈도우가 보여줄 현재 상태. 저장된 설정(프로젝트·이슈 유형·상태), 현재 작업 번호,
 * 현재 Git 브랜치를 한자리에 모은다. 이메일과 API 토큰은 담지 않는다.
 */
data class JiraTaskStatus(
    val projectKey: String?,
    val baseUrl: String?,
    val projectName: String?,
    val storyIssueType: String?,
    val storyStartStatus: String?,
    val taskNumber: String?,
    val branch: String?,
    val problem: StatusProblem?,
) {
    /** 이슈 초안·브랜치·커밋을 실행할 수 있는 상태인지 — 프로젝트 키가 있어야 한다. */
    val isConfigured: Boolean get() = problem == null && !projectKey.isNullOrEmpty()

    /**
     * 화면에 그릴 항목들. 값이 없으면 무엇이 없는지 알 수 있는 문구를 넣고, 그것이 실제 값이 아니라는
     * 사실([StatusRow.isPlaceholder])과 어떻게 그려야 하는지([StatusRow.kind])를 함께 알려준다.
     */
    fun statusRows(): List<StatusRow> = listOf(
        row("프로젝트 키", projectKey, NOT_CONFIGURED, StatusRow.Kind.CODE),
        row("Jira 주소", baseUrl, NOT_CONFIGURED, StatusRow.Kind.LINK),
        row("프로젝트", projectName, NOT_CONFIGURED, StatusRow.Kind.TEXT),
        row("스토리 유형", storyIssueType, NOT_CONFIGURED, StatusRow.Kind.TEXT),
        row("스토리 시작 상태", storyStartStatus, NOT_CONFIGURED, StatusRow.Kind.TEXT),
        row("현재 작업 번호", taskNumber, NO_TASK, StatusRow.Kind.CODE),
        row("현재 브랜치", branch, NO_BRANCH, StatusRow.Kind.CODE),
    )

    /** 이름과 표시 문구만 필요한 곳(로그·간단한 검증)에서 쓰는 축약형. */
    fun rows(): List<Pair<String, String>> = statusRows().map { it.name to it.value }

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

        /** 툴 윈도우가 그릴 항목 이름. [rows]의 키와 항상 같다. */
        val ROW_NAMES: List<String> = EMPTY.rows().map { it.first }
    }
}

/** 설정 읽기 결과와 그때 읽은 작업 번호·브랜치로 현재 상태를 만든다. */
fun JiraTaskStatus.Companion.of(load: SettingsLoad, taskNumber: String?, branch: String?): JiraTaskStatus =
    when (load) {
        is SettingsLoad.Broken -> JiraTaskStatus(
            projectKey = null,
            baseUrl = null,
            projectName = null,
            storyIssueType = null,
            storyStartStatus = null,
            taskNumber = taskNumber,
            branch = branch,
            problem = StatusProblem(StatusProblem.Kind.BROKEN, load.message),
        )
        is SettingsLoad.Loaded -> load.snapshot.toStatus(taskNumber, branch)
    }

private fun SettingsSnapshot.toStatus(taskNumber: String?, branch: String?): JiraTaskStatus = JiraTaskStatus(
    projectKey = projectKey,
    baseUrl = baseUrl,
    projectName = projectName,
    storyIssueType = storyIssueType,
    storyStartStatus = storyStartStatus,
    taskNumber = taskNumber,
    branch = branch,
    problem = if (projectKey.isEmpty()) {
        StatusProblem(StatusProblem.Kind.NEEDS_SETUP, JiraTaskStatus.NEEDS_SETUP)
    } else {
        null
    },
)
