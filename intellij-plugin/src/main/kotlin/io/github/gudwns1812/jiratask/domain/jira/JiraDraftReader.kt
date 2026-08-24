package io.github.gudwns1812.jiratask.domain.jira

/** 작업이 속한 스토리에서 따올 수 있는 값들. 작업 이슈는 이 값을 그대로 물려받는다. */
data class JiraStoryContext(
    val summary: String? = null,
    val epicKey: String? = null,
    val sprint: String? = null,
)

/**
 * 이슈 초안을 사람이 다시 타이핑하지 않게 채우기 위한 읽기 전용 Jira 경계다.
 * 호출은 동기(블로킹)이므로 백그라운드 스레드에서 써야 한다.
 */
interface JiraDraftReader {
    /** 지금 연결된 사용자의 표시 이름. 담당자 기본값으로 쓴다. */
    fun currentUserName(): String?

    /** 이 Jira에 정의된 우선순위 이름들(높은 것부터). */
    fun listPriorities(): List<String>

    /** 스토리에서 물려받을 값(제목·에픽·스프린트). */
    fun storyContext(storyKey: String): JiraStoryContext
}
