package io.github.gudwns1812.jiratask.domain.jira

/**
 * 상태 카테고리. 상태 이름은 사이트 언어와 워크플로마다 다르지만 카테고리는 Jira가 보장한다.
 * 화면 정렬과 "끝난 작업인가" 판단은 이름이 아니라 이 값으로 한다(RULE.md 6.1).
 */
enum class JiraStatusCategory {
    TO_DO,
    IN_PROGRESS,
    DONE,
    /** Jira가 알려주지 않았거나 모르는 값. 끝난 것으로 보지 않는다. */
    UNKNOWN,
    ;

    val isDone: Boolean get() = this == DONE

    companion object {
        /** Jira가 주는 `statusCategory.key`. 이 세 값은 API가 고정으로 보장한다. */
        fun ofKey(key: String?): JiraStatusCategory = when (key?.trim()?.lowercase()) {
            "new", "undefined" -> TO_DO
            "indeterminate" -> IN_PROGRESS
            "done" -> DONE
            else -> UNKNOWN
        }
    }
}

/**
 * 고르거나 확인할 수 있는 작업 하나.
 *
 * [inOpenSprint]는 커밋 가드(RULE.md 5.2)가 "동결된 스프린트 밖의 일인가"를 판단하는 값이다.
 * 목록으로 받은 작업은 정의상 열린 스프린트 안에 있고, 키로 하나만 조회한 작업은 따로 확인한다.
 */
data class SprintTask(
    val key: String,
    val summary: String,
    val statusName: String,
    val statusCategory: JiraStatusCategory,
    val inOpenSprint: Boolean,
) {
    /** 목록에 보여줄 한 줄. `S15P11A108-257  [AUTH-001 | FE] 회원가입 화면 구현` */
    fun label(): String = if (summary.isBlank()) key else "$key  $summary"
}

/**
 * 스프린트의 작업을 읽는 경계.
 *
 * **두 함수의 거르는 범위가 다르다.**
 * - [currentSprintTasks]는 담당자로 좁힌다. 고를 대상을 주는 자리다.
 * - [task]는 아무것도 거르지 않는다. 남의 작업도, 끝난 작업도, 지난 스프린트의 작업도 그대로 준다.
 *
 * 어느 쪽도 **상태로 거르지 않는다.** 끝난 작업에도 후속 커밋이 붙고(리뷰 지적 반영, 후속 수정),
 * 그때 그 작업을 다시 고를 수단이 있어야 한다. 거르는 일은 화면에서 한다(RULE.md 6.1).
 *
 * 호출은 동기(블로킹)다. 액션은 백그라운드 스레드에서 호출해 EDT를 막지 않아야 한다.
 */
interface SprintTaskReader {

    /** 열린 스프린트에 담긴 내 작업. 끝난 것도 포함한다. */
    fun currentSprintTasks(projectKey: String, taskIssueTypeId: String): List<SprintTask>

    /** 이슈 하나. 없거나 볼 수 없으면 null. */
    fun task(issueKey: String): SprintTask?

    /** 제목에 [text]가 들어가는 이슈. 상태·스프린트·담당자로 좁히지 않는다(Quick Search). */
    fun searchByText(projectKey: String, text: String, limit: Int): List<SprintTask>
}

/** 이슈 상세. 검색 결과에서 하나를 열었을 때 보여 줄 것들. */
data class JiraIssueDetail(
    val key: String,
    val summary: String,
    val description: String,
    val statusName: String,
    val assignee: String,
    val sprint: String,
)

/**
 * 이슈 하나를 열어 보고 고치는 경계.
 *
 * **제목과 설명만 고칠 수 있다.** 확정된 스프린트에서 수정은 허용되지만 추가·삭제는 아니고
 * (RULE.md 3.2), 상태와 스프린트는 automation과 계획의 영역이다(RULE.md 6.2).
 * 그래서 이 포트에는 삭제도, 상태 변경도 없다.
 */
interface JiraIssueEditor {
    fun detail(issueKey: String): JiraIssueDetail?

    /** 제목과 설명을 저장한다. 다른 필드는 건드리지 않는다. */
    fun updateSummaryAndDescription(issueKey: String, summary: String, description: String)
}
