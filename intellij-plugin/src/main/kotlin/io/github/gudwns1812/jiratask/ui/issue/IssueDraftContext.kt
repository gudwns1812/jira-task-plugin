package io.github.gudwns1812.jiratask.ui.issue

import io.github.gudwns1812.jiratask.domain.policy.WorkPolicy
import io.github.gudwns1812.jiratask.domain.validation.TitleParser

/**
 * 작업 제목을 RULE.md 형식으로 조립한다. 사용자는 대괄호 안을 직접 타이핑하지 않고
 * 구분(기능 ID 또는 업무 분류)과 영역을 고르고 요약만 쓴다.
 */
object TaskTitles {

    /**
     * @param scope 기능 ID(`AUTH-001`) 또는 업무 분류(`PLAN`)
     * @param area 담당 영역(`FE`). 업무 분류에서는 생략할 수 있다
     */
    fun compose(scope: String, area: String?, summary: String): String {
        val trimmedScope = scope.trim()
        val trimmedArea = area?.trim().orEmpty()
        val trimmedSummary = summary.trim()

        val bracket = if (trimmedArea.isEmpty()) trimmedScope else "$trimmedScope | $trimmedArea"
        return "[$bracket] $trimmedSummary".trim()
    }

    /** 스토리 제목(`[AUTH-001] 이메일 회원가입`)에서 작업 제목에 쓸 기능 ID를 뽑는다. */
    fun featureIdOf(storySummary: String?): String? {
        val parsed = storySummary?.let { TitleParser.parseStoryTitle(it.trim()) } ?: return null
        return "${parsed.domainCode}-${parsed.sequence}"
    }

    /** 간단 모드의 "구분" 후보: 브랜치에서 알아낸 기능 ID가 있으면 맨 앞에, 그 뒤로 업무 분류들. */
    fun scopeChoices(featureId: String?, policy: WorkPolicy): List<String> =
        listOfNotNull(featureId) + policy.workCategories
}

/**
 * 이슈 생성 다이얼로그를 열 때 플러그인이 이미 아는 값들. 사용자가 다시 타이핑하지 않도록
 * 현재 브랜치·Jira 스토리·지난번 입력에서 모아온다.
 */
data class IssueDraftContext(
    val projectKey: String,
    val policy: WorkPolicy,
    val storyKey: String? = null,
    /** 스토리의 에픽. 작업은 스토리와 같은 에픽에 속하므로 사람이 입력할 값이 아니다. */
    val epicKey: String? = null,
    val featureId: String? = null,
    /** 스토리가 들어 있는 스프린트. 작업도 같은 스프린트에서 진행한다. */
    val sprint: String? = null,
    val branch: String? = null,
    /** 지금 Jira에 연결된 사용자 — 담당자 기본값. */
    val currentUser: String? = null,
    /** 이 Jira에 정의된 우선순위 목록. */
    val priorities: List<String> = DEFAULT_PRIORITIES,
    val remembered: RememberedInput = RememberedInput(),
    /** 스토리를 조회하지 못한 이유(권한·네트워크 등). 화면에서 조용히 안내만 한다. */
    val lookupNote: String? = null,
) {
    fun scopeChoices(): List<String> = TaskTitles.scopeChoices(featureId, policy)

    /** 처음 열었을 때 고를 구분 — 기능 ID를 알면 그것, 아니면 지난번에 쓴 값. */
    fun defaultScope(): String? = featureId ?: remembered.scope ?: policy.workCategories.firstOrNull()

    fun defaultArea(): String? = remembered.area ?: policy.areas.firstOrNull()

    fun defaultStoryPoints(): Int? = remembered.storyPoints ?: policy.storyPoints.firstOrNull()

    /** 담당자는 나 자신이 기본이다. 지난번에 다른 사람을 지정했다면 그 값을 잇는다. */
    fun defaultAssignee(): String? = remembered.assignee ?: currentUser

    /** 스프린트는 스토리에서 물려받고, 알 수 없으면 지난번 값을 쓴다. */
    fun defaultSprint(): String? = sprint ?: remembered.sprint

    /** 우선순위 기본값은 가장 낮은 쪽(Low) — 급한 일만 사람이 올려 잡게 한다. */
    fun defaultPriority(): String? = remembered.priority
        ?: priorities.firstOrNull { it.equals(PREFERRED_PRIORITY, ignoreCase = true) }
        ?: priorities.lastOrNull()

    companion object {
        const val PREFERRED_PRIORITY: String = "Low"

        /** Jira에서 목록을 읽지 못했을 때 쓰는 표준 우선순위. */
        val DEFAULT_PRIORITIES: List<String> = listOf("Highest", "High", "Medium", "Low", "Lowest")
    }
}

/** 프로젝트별로 기억해 둔 지난번 입력. 스프린트 중에는 거의 바뀌지 않는 값들이다. */
data class RememberedInput(
    val scope: String? = null,
    val area: String? = null,
    val assignee: String? = null,
    val priority: String? = null,
    val storyPoints: Int? = null,
    val sprint: String? = null,
    val dueDate: String? = null,
    val epicKey: String? = null,
)
