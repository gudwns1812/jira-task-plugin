package io.github.gudwns1812.jiratask.ui.issue

import io.github.gudwns1812.jiratask.domain.branch.BranchNames
import io.github.gudwns1812.jiratask.domain.jira.StoryIssueReader
import io.github.gudwns1812.jiratask.ui.IssueDraftSettings

/**
 * 다이얼로그를 열기 전에 채울 값을 모은다. 현재 브랜치에서 스토리 번호를 되짚고, 그 스토리를
 * Jira에서 읽어 기능 ID(`AUTH-001`)와 에픽 키를 가져온다.
 *
 * 네트워크를 쓰므로 반드시 백그라운드 스레드에서 호출해야 한다. 조회가 실패해도 예외를 던지지 않고
 * 아는 값까지만 채운 컨텍스트를 돌려준다 — 설정을 못 읽는 것과 달리 이슈 생성을 막을 이유는 없다.
 */
class IssueDraftContextReader(
    private val settings: IssueDraftSettings,
    private val remembered: RememberedInput,
    private val branch: String?,
    private val createStoryReader: (() -> StoryIssueReader)?,
) {
    fun read(): IssueDraftContext {
        val base = IssueDraftContext(
            projectKey = settings.projectKey,
            policy = settings.policy,
            branch = branch,
            remembered = remembered,
            epicKey = remembered.epicKey,
        )

        val storyNumber = BranchNames.storyNumberOf(branch)
            ?: return base.copy(lookupNote = NO_STORY_BRANCH)
        val storyKey = "${settings.projectKey}-$storyNumber"
        val reader = createStoryReader?.invoke()
            ?: return base.copy(storyKey = storyKey, lookupNote = NO_CONNECTION)

        return try {
            val issue = reader.getIssue(storyKey)
            base.copy(
                storyKey = storyKey,
                featureId = TaskTitles.featureIdOf(issue.summary),
                lookupNote = if (TaskTitles.featureIdOf(issue.summary) == null) noFeatureId(storyKey) else null,
            )
        } catch (error: Exception) {
            base.copy(storyKey = storyKey, lookupNote = lookupFailed(storyKey))
        }
    }

    companion object {
        const val NO_STORY_BRANCH: String =
            "현재 브랜치가 feat/<스토리 번호>-<이름> 형식이 아니어서 스토리를 알 수 없습니다."
        const val NO_CONNECTION: String = "Jira 연결 정보가 없어 스토리 제목을 읽지 못했습니다."

        fun noFeatureId(storyKey: String): String = "$storyKey 제목에서 기능 ID를 찾지 못했습니다."

        fun lookupFailed(storyKey: String): String = "$storyKey 를 Jira에서 읽지 못했습니다."
    }
}
