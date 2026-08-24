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
    val epicKey: String? = null,
    val featureId: String? = null,
    val branch: String? = null,
    val remembered: RememberedInput = RememberedInput(),
    /** 스토리를 조회하지 못한 이유(권한·네트워크 등). 화면에서 조용히 안내만 한다. */
    val lookupNote: String? = null,
) {
    fun scopeChoices(): List<String> = TaskTitles.scopeChoices(featureId, policy)

    /** 처음 열었을 때 고를 구분 — 기능 ID를 알면 그것, 아니면 지난번에 쓴 값. */
    fun defaultScope(): String? = featureId ?: remembered.scope ?: policy.workCategories.firstOrNull()

    fun defaultArea(): String? = remembered.area ?: policy.areas.firstOrNull()

    fun defaultStoryPoints(): Int? = remembered.storyPoints ?: policy.storyPoints.firstOrNull()
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
