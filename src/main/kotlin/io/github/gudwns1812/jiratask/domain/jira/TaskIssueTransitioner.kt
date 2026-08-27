package io.github.gudwns1812.jiratask.domain.jira

/** 이슈에서 지금 실행할 수 있는 워크플로 전환 하나. */
data class JiraTransition(
    val id: String,
    val toStatusId: String,
    val toStatusName: String,
    /** 옮겨 갈 상태의 카테고리. 어느 전환이 "시작"인지 이 값으로 고른다. */
    val toStatusCategory: JiraStatusCategory = JiraStatusCategory.UNKNOWN,
)

/**
 * 작업 이슈의 상태를 옮기는 데 필요한 것만 모은 포트.
 *
 * 전환 목록을 그대로 넘겨받는다. 이슈 유형마다 워크플로가 달라 설정에 저장해 둔 상태 ID가
 * 작업 워크플로에는 없을 수 있어, 상태 이름으로도 찾아야 하기 때문이다.
 *
 * 호출은 동기(블로킹)다. 액션은 백그라운드 스레드에서 호출해 EDT를 막지 않아야 한다.
 */
interface TaskIssueTransitioner {
    fun getIssue(issueKey: String): JiraIssue
    fun listTransitions(issueKey: String): List<JiraTransition>
    fun transition(issueKey: String, transitionId: String)
}
