package io.github.gudwns1812.jiratask.ui.toolwindow

import io.github.gudwns1812.jiratask.application.SettingsLoad
import io.github.gudwns1812.jiratask.application.SettingsSnapshot

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
    val problem: String?,
) {
    /** 이슈 초안·브랜치·커밋을 실행할 수 있는 상태인지 — 프로젝트 키가 있어야 한다. */
    val isConfigured: Boolean get() = problem == null && !projectKey.isNullOrEmpty()

    /** 화면에 그릴 항목들. 값이 없으면 무엇이 없는지 알 수 있는 문구를 넣는다. */
    fun rows(): List<Pair<String, String>> = listOf(
        "프로젝트 키" to projectKey.orPlaceholder(NOT_CONFIGURED),
        "Jira 주소" to baseUrl.orPlaceholder(NOT_CONFIGURED),
        "프로젝트" to projectName.orPlaceholder(NOT_CONFIGURED),
        "스토리 유형" to storyIssueType.orPlaceholder(NOT_CONFIGURED),
        "스토리 시작 상태" to storyStartStatus.orPlaceholder(NOT_CONFIGURED),
        "현재 작업 번호" to taskNumber.orPlaceholder(NO_TASK),
        "현재 브랜치" to branch.orPlaceholder(NO_BRANCH),
    )

    private fun String?.orPlaceholder(placeholder: String): String =
        if (isNullOrBlank()) placeholder else this

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
            problem = load.message,
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
    problem = if (projectKey.isEmpty()) JiraTaskStatus.NEEDS_SETUP else null,
)
