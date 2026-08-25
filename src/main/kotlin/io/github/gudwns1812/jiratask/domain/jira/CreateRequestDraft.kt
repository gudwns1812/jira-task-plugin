package io.github.gudwns1812.jiratask.domain.jira

/** Jira 사이트별 필드 ID를 적용하기 전의 생성 요청 초안이다. */
data class CreateRequestDraft(
    val projectKey: String,
    val issueType: String,
    val summary: String,
    val description: String?,
    val labels: List<String>,
    val assignee: String? = null,
    val priority: String? = null,
    val dueDate: String? = null,
    val policyFields: PolicyFields,
    val relationships: Relationships,
) {
    data class PolicyFields(
        val storyPoints: Int? = null,
        val sprint: String? = null,
    )

    data class Relationships(
        val epicKey: String? = null,
        val storyKey: String? = null,
    )
}
