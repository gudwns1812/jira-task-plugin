package io.github.gudwns1812.jiratask.domain.jira

data class JiraTransition(
    val id: String,
    val toStatusId: String,
    val toStatusName: String,
    val toStatusCategory: JiraStatusCategory = JiraStatusCategory.UNKNOWN,
)

interface TaskIssueTransitioner {
    fun getIssue(issueKey: String): JiraIssue
    fun listTransitions(issueKey: String): List<JiraTransition>
    fun transition(issueKey: String, transitionId: String)
}
