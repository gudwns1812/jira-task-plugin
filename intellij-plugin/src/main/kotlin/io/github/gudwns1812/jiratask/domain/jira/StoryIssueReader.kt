package io.github.gudwns1812.jiratask.domain.jira

data class JiraIssue(
    val key: String,
    val issueTypeId: String,
    val issueTypeName: String,
    val statusId: String,
    val statusName: String,
)

/**
 * 호출은 동기(블로킹)로 설계했다. IntelliJ 플랫폼에서는 액션이 이 포트를
 * `Task.Backgroundable` 등 백그라운드 스레드에서 호출해 EDT를 막지 않아야 한다.
 */
interface StoryIssueReader {
    fun getIssue(issueKey: String): JiraIssue
    fun findTransitionId(issueKey: String, targetStatusId: String): String?
    fun transition(issueKey: String, transitionId: String)
}

class JiraAuthenticationError(message: String = "Jira 인증 또는 권한을 확인할 수 없습니다.") : Exception(message)

class JiraNetworkError(message: String = "Jira 서버와 통신할 수 없습니다.") : Exception(message)

class JiraRequestError(message: String) : Exception(message)
