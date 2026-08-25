package io.github.gudwns1812.jiratask.domain.jira

data class JiraIssue(
    val key: String,
    val issueTypeId: String,
    val issueTypeName: String,
    val statusId: String,
    val statusName: String,
    val summary: String = "",
)

class JiraAuthenticationError(message: String = "Jira 인증 또는 권한을 확인할 수 없습니다.") : Exception(message)

class JiraNetworkError(message: String = "Jira 서버와 통신할 수 없습니다.") : Exception(message)

class JiraRequestError(message: String) : Exception(message)
