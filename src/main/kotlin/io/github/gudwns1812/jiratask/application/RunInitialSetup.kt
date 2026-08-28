package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.jira.JiraAuthenticationError
import io.github.gudwns1812.jiratask.domain.jira.JiraIssueTypeWithStatuses
import io.github.gudwns1812.jiratask.domain.jira.JiraNamedValue
import io.github.gudwns1812.jiratask.domain.jira.JiraProject
import io.github.gudwns1812.jiratask.domain.jira.JiraSetupReader
import io.github.gudwns1812.jiratask.domain.policy.JiraProjectRef
import io.github.gudwns1812.jiratask.domain.policy.JiraSettings
import java.net.URI
import java.net.URISyntaxException

data class SetupConnection(val baseUrl: String, val email: String, val apiToken: String) {
    companion object
}

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
)

/** API 토큰을 설정 파일과 분리해 보관하는 저장소의 경계다. 실제 구현은 인프라 계층이 제공한다. */
interface CredentialStore {
    fun save(baseUrl: String, email: String, apiToken: String)
    fun get(baseUrl: String, email: String): String?
}

data class InitialSetupResult(val projectKey: String, val storyIssueType: String)

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

        val summary = SetupSummary(
            baseUrl = connection.baseUrl,
            project = project,
            storyIssueType = toNamedValue(storyIssueType),
            taskIssueType = toNamedValue(taskIssueType),
            epicIssueType = toNamedValue(epicIssueType),
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
        )

        credentials.save(connection.baseUrl, connection.email, connection.apiToken)
        saveConfig(project.key, jiraSettings)

        return InitialSetupResult(project.key, storyIssueType.name)
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

class SetupInputError(message: String) : Exception(message)

/**
 * 연결 입력값의 문제를 사람이 읽을 문구로 돌려준다. 문제가 없으면 null.
 * 마법사 폼이 입력 중에도 같은 규칙으로 안내할 수 있도록 검증만 분리했다(CLI 프롬프트의 validate와 동일).
 */
fun SetupConnection.Companion.problemOf(baseUrl: String, email: String, apiToken: String): String? = when {
    baseUrl.isBlank() -> "Jira 주소를 입력하세요."
    !isHttpUrl(baseUrl.trim()) -> "https://team.atlassian.net 형식으로 입력하세요."
    email.isBlank() -> "Jira 이메일을 입력하세요."
    apiToken.isEmpty() -> "Jira API Token을 입력하세요."
    else -> null
}

/** 검증한 뒤 정규화(주소 끝 슬래시 제거, 공백 제거)한 연결 정보를 만든다. */
fun SetupConnection.Companion.of(baseUrl: String, email: String, apiToken: String): SetupConnection {
    SetupConnection.problemOf(baseUrl, email, apiToken)?.let { throw SetupInputError(it) }
    return SetupConnection(baseUrl.trim().trimEnd('/'), email.trim(), apiToken)
}

/** 최종 확인 화면에 보여줄 항목들. 이메일과 API 토큰은 담지 않는다. */
fun SetupSummary.toDisplayLines(): List<Pair<String, String>> = listOf(
    "Jira 주소" to baseUrl,
    "프로젝트" to "${project.key} - ${project.name}",
    "스토리 유형" to storyIssueType.name,
    "작업 유형" to taskIssueType.name,
    "에픽 유형" to epicIssueType.name,
)

private fun isHttpUrl(value: String): Boolean = try {
    val scheme = URI(value).scheme
    scheme == "http" || scheme == "https"
} catch (error: URISyntaxException) {
    false
}
