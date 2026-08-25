package io.github.gudwns1812.jiratask.domain.jira

/** 사이트마다 ID가 다른 커스텀 필드들. 이름으로 찾아 둔 결과다. */
data class JiraFieldIds(
    val storyPoints: String? = null,
    val sprint: String? = null,
)

/** 만들어진 이슈. */
data class JiraCreatedIssue(val key: String)

/**
 * 이슈를 실제로 만드는 경계. CLI에는 없던 기능이라 이식이 아니라 새로 정의한 포트다.
 * 호출은 동기(블로킹)이므로 백그라운드 스레드에서 써야 한다.
 */
interface JiraIssueCreator {
    /** `fields`에 그대로 들어갈 값들을 받아 이슈를 만든다. */
    fun createIssue(fields: Map<String, Any?>): JiraCreatedIssue

    /** 이름·이메일로 사용자를 찾아 accountId를 돌려준다(담당자 지정에 필요). */
    fun findAccountId(query: String): String?

    /** 스토리 포인트·스프린트 커스텀 필드 ID를 이름으로 찾는다. */
    fun findFieldIds(): JiraFieldIds

    /**
     * 프로젝트의 보드에서 이름이 정확히 일치하는 진행 중·예정 스프린트 ID를 찾는다.
     * 같은 이름의 서로 다른 스프린트가 있거나 찾지 못하면 잘못 지정하지 않도록 `null`을 돌려준다.
     */
    fun findSprintId(projectKey: String, sprintName: String): Int?

    /** 두 이슈를 연결한다(RULE.md의 스토리 → 작업 분할 관계). */
    fun linkIssues(inwardKey: String, outwardKey: String, linkTypeName: String)
}
