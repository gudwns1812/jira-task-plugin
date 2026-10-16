package io.github.gudwns1812.jiratask.domain.jira

data class JiraProject(val id: String, val key: String, val name: String)

data class JiraNamedValue(val id: String, val name: String)

data class JiraIssueTypeWithStatuses(
    val id: String,
    val name: String,
    val statuses: List<JiraNamedValue>,
)

interface JiraSetupReader {
    fun verifyIdentity()
    fun listProjects(): List<JiraProject>
    fun listIssueTypesWithStatuses(projectKey: String): List<JiraIssueTypeWithStatuses>
}
