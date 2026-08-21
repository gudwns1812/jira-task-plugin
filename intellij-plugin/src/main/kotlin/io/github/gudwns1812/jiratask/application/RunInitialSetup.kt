package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.jira.JiraAuthenticationError
import io.github.gudwns1812.jiratask.domain.jira.JiraIssueTypeWithStatuses
import io.github.gudwns1812.jiratask.domain.jira.JiraNamedValue
import io.github.gudwns1812.jiratask.domain.jira.JiraProject
import io.github.gudwns1812.jiratask.domain.jira.JiraSetupReader
import io.github.gudwns1812.jiratask.domain.policy.JiraProjectRef
import io.github.gudwns1812.jiratask.domain.policy.JiraSettings

data class SetupConnection(val baseUrl: String, val email: String, val apiToken: String)

data class SetupChoice(val value: String, val label: String)

interface SetupPrompter {
    fun askConnection(): SetupConnection
    fun choose(message: String, choices: List<SetupChoice>): String
    fun confirm(summary: SetupSummary): Boolean
    fun showSuccess(message: String)
    fun showError(message: String)
}

data class SetupSummary(
    val baseUrl: String,
    val project: JiraProject,
    val storyIssueType: JiraNamedValue,
    val taskIssueType: JiraNamedValue,
    val epicIssueType: JiraNamedValue,
    val storyStartStatus: JiraNamedValue,
    val taskDoneStatus: JiraNamedValue,
)

/** API 토큰을 설정 파일과 분리해 보관하는 저장소의 경계다. 실제 구현은 인프라 계층이 제공한다. */
interface CredentialStore {
    fun save(baseUrl: String, email: String, apiToken: String)
    fun get(baseUrl: String, email: String): String?
}

data class InitialSetupResult(val projectKey: String, val storyIssueType: String, val storyStartStatus: String)

class SetupCancelledError : Exception("초기 설정을 취소했습니다.")

class SetupSelectionError(message: String) : Exception(message)

class RunInitialSetupUseCase(
    private val prompter: SetupPrompter,
    private val createJira: (SetupConnection) -> JiraSetupReader,
    private val saveConfig: (projectKey: String, jira: JiraSettings) -> Unit,
    private val credentials: CredentialStore,
) {
    fun execute(): InitialSetupResult {
        val (connection, jira) = verifiedConnection()
        prompter.showSuccess("✓ Jira 연결 성공")

        val projects = jira.listProjects()
        if (projects.isEmpty()) {
            throw SetupSelectionError("접근할 수 있는 Jira 프로젝트가 없습니다.")
        }

        val project = selectProject(
            projects,
            prompter.choose("프로젝트를 선택하세요.", projects.map { SetupChoice(it.id, "${it.key} - ${it.name}") }),
        )

        val issueTypes = jira.listIssueTypesWithStatuses(project.key)
        val storyIssueType = findIssueTypeByNames(issueTypes, STORY_ISSUE_TYPE_NAMES, "스토리")
        val taskIssueType = findIssueTypeByNames(issueTypes, TASK_ISSUE_TYPE_NAMES, "작업")
        val epicIssueType = findIssueTypeByNames(issueTypes, EPIC_ISSUE_TYPE_NAMES, "에픽")
        if (setOf(storyIssueType.id, taskIssueType.id, epicIssueType.id).size != 3) {
            throw SetupSelectionError("서로 다른 스토리, 작업, 에픽 이슈 유형을 찾을 수 없습니다.")
        }

        val storyStartStatus = selectStatus("스토리 브랜치 생성 시 이동할 상태를 선택하세요.", storyIssueType, "start")
        val taskDoneStatus = selectStatus("작업 완료 시 이동할 상태를 선택하세요.", taskIssueType, "done")

        val summary = SetupSummary(
            baseUrl = connection.baseUrl,
            project = project,
            storyIssueType = toNamedValue(storyIssueType),
            taskIssueType = toNamedValue(taskIssueType),
            epicIssueType = toNamedValue(epicIssueType),
            storyStartStatus = storyStartStatus,
            taskDoneStatus = taskDoneStatus,
        )
        if (!prompter.confirm(summary)) {
            throw SetupCancelledError()
        }

        val jiraSettings = JiraSettings(
            baseUrl = connection.baseUrl,
            email = connection.email,
            project = JiraProjectRef(project.id, project.name),
            issueTypes = JiraSettings.IssueTypes(
                story = toNamedValue(storyIssueType),
                task = toNamedValue(taskIssueType),
                epic = toNamedValue(epicIssueType),
            ),
            statuses = JiraSettings.Statuses(
                story = JiraSettings.Statuses.StoryStatuses(start = storyStartStatus),
                task = JiraSettings.Statuses.TaskStatuses(done = taskDoneStatus),
            ),
        )

        credentials.save(connection.baseUrl, connection.email, connection.apiToken)
        saveConfig(project.key, jiraSettings)

        return InitialSetupResult(project.key, storyIssueType.name, storyStartStatus.name)
    }

    private fun verifiedConnection(): Pair<SetupConnection, JiraSetupReader> {
        while (true) {
            val connection = prompter.askConnection()
            val jira = createJira(connection)
            try {
                jira.verifyIdentity()
                return connection to jira
            } catch (error: JiraAuthenticationError) {
                prompter.showError("Jira 인증에 실패했습니다. 이메일과 API 토큰을 다시 확인하세요.")
            }
        }
    }

    private fun findIssueTypeByNames(
        issueTypes: List<JiraIssueTypeWithStatuses>,
        names: Set<String>,
        label: String,
    ): JiraIssueTypeWithStatuses {
        val matches = issueTypes.filter { it.name.trim().lowercase() in names }

        return when {
            matches.size == 1 -> matches.single()
            matches.size > 1 -> throw SetupSelectionError(
                "${label}로 사용할 이슈 유형이 여러 개입니다: ${matches.joinToString(", ") { it.name }}. 프로젝트의 이슈 타입 이름을 정리한 뒤 다시 시도하세요.",
            )
            else -> throw SetupSelectionError("이 프로젝트에는 '${names.joinToString("' 또는 '")}' 이슈 유형이 없습니다.")
        }
    }

    private fun selectStatus(message: String, issueType: JiraIssueTypeWithStatuses, purpose: String): JiraNamedValue {
        if (issueType.statuses.isEmpty()) {
            throw SetupSelectionError("${issueType.name} 이슈 유형에는 $purpose 상태를 선택할 수 없습니다.")
        }
        val selectedId = prompter.choose(message, issueType.statuses.map { SetupChoice(it.id, it.name) })
        return issueType.statuses.find { it.id == selectedId }
            ?: throw SetupSelectionError("선택한 상태가 현재 이슈 유형에 없습니다.")
    }

    private fun selectProject(projects: List<JiraProject>, selectedId: String): JiraProject =
        projects.find { it.id == selectedId } ?: throw SetupSelectionError("선택한 프로젝트에 접근할 수 없습니다.")

    private fun toNamedValue(value: JiraIssueTypeWithStatuses): JiraNamedValue = JiraNamedValue(value.id, value.name)

    companion object {
        private val STORY_ISSUE_TYPE_NAMES = setOf("스토리", "story")
        private val TASK_ISSUE_TYPE_NAMES = setOf("작업", "task")
        private val EPIC_ISSUE_TYPE_NAMES = setOf("에픽", "epic")
    }
}
