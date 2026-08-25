package io.github.gudwns1812.jiratask.ui.issue

import io.github.gudwns1812.jiratask.domain.branch.BranchNames
import io.github.gudwns1812.jiratask.domain.jira.JiraDraftReader
import io.github.gudwns1812.jiratask.domain.jira.JiraStoryContext
import io.github.gudwns1812.jiratask.ui.IssueDraftSettings

/**
 * 다이얼로그를 열기 전에 채울 값을 모은다. 사람이 다시 입력할 이유가 없는 값들이다.
 *
 * - 스토리 키 ← 현재 브랜치(`feat/100-signup` → `<프로젝트 키>-100`)
 * - 기능 ID·에픽 키·스프린트 ← 그 스토리(작업은 스토리와 같은 에픽·스프린트에 속한다)
 * - 담당자 ← 지금 연결된 Jira 사용자
 * - 우선순위 목록 ← 이 Jira에 정의된 값들
 *
 * 네트워크를 쓰므로 반드시 백그라운드 스레드에서 호출해야 한다. 조회가 실패해도 예외를 던지지 않고
 * 아는 값까지만 채운 컨텍스트를 돌려준다 — 이슈 생성을 막을 이유는 없다.
 */
class IssueDraftContextReader(
    private val settings: IssueDraftSettings,
    private val remembered: RememberedInput,
    private val branch: String?,
    private val createReader: (() -> JiraDraftReader)?,
) {
    fun read(): IssueDraftContext {
        val reader = createReader?.invoke()
        val base = IssueDraftContext(
            projectKey = settings.projectKey,
            policy = settings.policy,
            branch = branch,
            remembered = remembered,
            epicKey = remembered.epicKey,
            currentUser = reader?.let { runCatching { it.currentUserName() }.getOrNull() },
            priorities = reader?.let { runCatching { it.listPriorities() }.getOrNull() }
                ?.takeIf { it.isNotEmpty() }
                ?: IssueDraftContext.DEFAULT_PRIORITIES,
        )

        val storyNumber = BranchNames.storyNumberOf(branch)
            ?: return base.copy(lookupNote = NO_STORY_BRANCH)
        val storyKey = "${settings.projectKey}-$storyNumber"
        if (reader == null) {
            return base.copy(storyKey = storyKey, lookupNote = NO_CONNECTION)
        }

        val story = try {
            reader.storyContext(storyKey)
        } catch (error: Exception) {
            return base.copy(storyKey = storyKey, lookupNote = lookupFailed(storyKey))
        }

        return base.withStory(storyKey, story)
    }

    private fun IssueDraftContext.withStory(storyKey: String, story: JiraStoryContext): IssueDraftContext {
        val featureId = TaskTitles.featureIdOf(story.summary)
        return copy(
            storyKey = storyKey,
            featureId = featureId,
            epicKey = story.epicKey ?: epicKey,
            sprint = story.sprint,
            sprintId = story.sprintId,
            lookupNote = if (featureId == null) noFeatureId(storyKey) else null,
        )
    }

    companion object {
        const val NO_STORY_BRANCH: String =
            "현재 브랜치가 feat/<스토리 번호>-<이름> 형식이 아니어서 스토리를 알 수 없습니다."
        const val NO_CONNECTION: String = "Jira 연결 정보가 없어 스토리 정보를 읽지 못했습니다."

        fun noFeatureId(storyKey: String): String = "$storyKey 제목에서 기능 ID를 찾지 못했습니다."

        fun lookupFailed(storyKey: String): String = "$storyKey 를 Jira에서 읽지 못했습니다."
    }
}
