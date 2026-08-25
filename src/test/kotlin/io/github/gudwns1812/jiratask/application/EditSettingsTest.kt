package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.jira.JiraNamedValue
import io.github.gudwns1812.jiratask.domain.policy.JiraProjectRef
import io.github.gudwns1812.jiratask.domain.policy.JiraSettings
import io.github.gudwns1812.jiratask.domain.policy.JiraTaskConfig
import io.github.gudwns1812.jiratask.domain.policy.PolicyConfigurationError
import io.github.gudwns1812.jiratask.domain.policy.default
import io.github.gudwns1812.jiratask.domain.policy.mergeWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EditSettingsTest {

    /** 실제 저장소와 같은 검증·병합 규칙(도메인 `mergeWith`)을 쓰되 파일·서비스 없이 동작하는 가짜 저장소. */
    private class FakeConfigStore(initial: JiraTaskConfig = JiraTaskConfig.default()) {
        var config: JiraTaskConfig = initial
            private set
        var saveCount: Int = 0
            private set
        var loadFailure: PolicyConfigurationError? = null

        fun load(): JiraTaskConfig = loadFailure?.let { throw it } ?: config

        fun save(update: Map<String, Any?>) {
            val merged = config.mergeWith(update)
            config = merged
            saveCount++
        }
    }

    private fun useCaseOf(store: FakeConfigStore) = EditSettingsUseCase(store::load, store::save)

    private fun configuredStore() = FakeConfigStore(
        JiraTaskConfig.default().mergeWith(
            mapOf(
                "projectKey" to "S15P11A108",
                "jira" to mapOf(
                    "baseUrl" to "https://team.atlassian.net",
                    "email" to "gudwns1423@gmail.com",
                    "project" to mapOf("id" to "10000", "name" to "A108 프로젝트"),
                    "issueTypes" to mapOf(
                        "story" to mapOf("id" to "10001", "name" to "스토리"),
                        "task" to mapOf("id" to "10002", "name" to "작업"),
                        "epic" to mapOf("id" to "10003", "name" to "에픽"),
                    ),
                    "statuses" to mapOf(
                        "story" to mapOf("start" to mapOf("id" to "21", "name" to "진행 중")),
                        "task" to mapOf("done" to mapOf("id" to "31", "name" to "완료")),
                    ),
                ),
            ),
        ),
    )

    private fun loadedSnapshot(store: FakeConfigStore): SettingsSnapshot =
        (useCaseOf(store).load() as SettingsLoad.Loaded).snapshot

    @Test
    fun `loads an empty snapshot with the default policy when nothing is configured`() {
        val snapshot = loadedSnapshot(FakeConfigStore())

        assertEquals("", snapshot.projectKey)
        assertEquals("", snapshot.baseUrl)
        assertEquals("", snapshot.projectName)
        assertEquals("", snapshot.storyIssueType)
        assertEquals(listOf("FE", "BE", "AI", "QA", "INFRA"), snapshot.areas)
        assertEquals(listOf(1, 2, 3, 5, 8), snapshot.storyPoints)
    }

    @Test
    fun `loads the configured project, issue types and statuses`() {
        val snapshot = loadedSnapshot(configuredStore())

        assertEquals("S15P11A108", snapshot.projectKey)
        assertEquals("https://team.atlassian.net", snapshot.baseUrl)
        assertEquals("A108 프로젝트", snapshot.projectName)
        assertEquals("스토리", snapshot.storyIssueType)
        assertEquals("작업", snapshot.taskIssueType)
        assertEquals("에픽", snapshot.epicIssueType)
        assertEquals("진행 중", snapshot.storyStartStatus)
        assertEquals("완료", snapshot.taskDoneStatus)
    }

    @Test
    fun `never exposes the configured email in the snapshot`() {
        val store = configuredStore()
        val snapshot = loadedSnapshot(store)

        assertEquals("gudwns1423@gmail.com", store.config.jira?.email)
        val shownValues = listOf(
            snapshot.projectKey,
            snapshot.baseUrl,
            snapshot.projectName,
            snapshot.storyIssueType,
            snapshot.taskIssueType,
            snapshot.epicIssueType,
            snapshot.storyStartStatus,
            snapshot.taskDoneStatus,
        ) + snapshot.areas + snapshot.workCategories
        assertTrue(shownValues.none { it.contains("gudwns1423") })
    }

    @Test
    fun `saves a trimmed project key and base url`() {
        val store = FakeConfigStore()

        val saved = useCaseOf(store).save(SettingsEdit("  S15P11A108  ", "  https://team.atlassian.net  "))

        assertEquals("S15P11A108", saved.projectKey)
        assertEquals("https://team.atlassian.net", saved.baseUrl)
        assertEquals("S15P11A108", store.config.projectKey)
        assertEquals(1, store.saveCount)
    }

    @Test
    fun `keeps existing issue types and statuses when only the base url changes`() {
        val store = configuredStore()

        val saved = useCaseOf(store).save(SettingsEdit("", "https://another.atlassian.net"))

        assertEquals("https://another.atlassian.net", saved.baseUrl)
        assertEquals("S15P11A108", saved.projectKey)
        assertEquals("스토리", saved.storyIssueType)
        assertEquals("진행 중", saved.storyStartStatus)
        assertEquals(JiraNamedValue("31", "완료"), store.config.jira?.statuses?.task?.done)
        assertEquals("gudwns1423@gmail.com", store.config.jira?.email)
    }

    @Test
    fun `does not touch the repository when both fields are blank`() {
        val store = configuredStore()

        val saved = useCaseOf(store).save(SettingsEdit("   ", ""))

        assertEquals(0, store.saveCount)
        assertEquals("S15P11A108", saved.projectKey)
        assertEquals("https://team.atlassian.net", saved.baseUrl)
    }

    @Test
    fun `rejects an invalid project key without saving`() {
        val store = configuredStore()

        val error = assertThrows(PolicyConfigurationError::class.java) {
            useCaseOf(store).save(SettingsEdit("s15p11a108", ""))
        }

        assertTrue(error.message!!.contains("projectKey"))
        assertEquals(0, store.saveCount)
        assertEquals("S15P11A108", store.config.projectKey)
    }

    @Test
    fun `rejects a base url that is not http without saving`() {
        val store = configuredStore()

        val error = assertThrows(PolicyConfigurationError::class.java) {
            useCaseOf(store).save(SettingsEdit("", "ftp://team.atlassian.net"))
        }

        assertTrue(error.message!!.contains("jira.baseUrl"))
        assertEquals(0, store.saveCount)
        assertEquals("https://team.atlassian.net", store.config.jira?.baseUrl)
    }

    @Test
    fun `reports a broken config instead of throwing`() {
        val store = FakeConfigStore().apply { loadFailure = PolicyConfigurationError("정책 설정을 읽을 수 없습니다.") }

        val load = useCaseOf(store).load()

        assertTrue(load is SettingsLoad.Broken)
        assertEquals("정책 설정을 읽을 수 없습니다.", (load as SettingsLoad.Broken).message)
    }

    @Test
    fun `treats only non blank changed fields as modifications`() {
        val snapshot = loadedSnapshot(configuredStore())

        assertFalse(SettingsEdit("S15P11A108", "https://team.atlassian.net").changes(snapshot))
        assertFalse("공백 필드는 변경 없음으로 본다", SettingsEdit("", "").changes(snapshot))
        assertFalse("값을 지운 것은 삭제가 아니라 변경 없음이다", SettingsEdit("  ", "  ").changes(snapshot))
        assertTrue(SettingsEdit("OTHER", "").changes(snapshot))
        assertTrue(SettingsEdit("", "https://another.atlassian.net").changes(snapshot))
    }

    @Test
    fun `builds a config update only for the filled fields`() {
        assertEquals(emptyMap<String, Any?>(), SettingsEdit(" ", " ").toConfigUpdate())
        assertEquals(mapOf("projectKey" to "ABC"), SettingsEdit("ABC", "").toConfigUpdate())
        assertEquals(
            mapOf("jira" to mapOf("baseUrl" to "https://team.atlassian.net")),
            SettingsEdit("", "https://team.atlassian.net").toConfigUpdate(),
        )
    }

    @Test
    fun `an empty snapshot exposes the default policy`() {
        val snapshot = SettingsSnapshot.empty()

        assertEquals("", snapshot.projectKey)
        assertEquals(listOf("PLAN", "UX", "DESIGN", "TECH", "INFRA", "DOCS", "QA", "PT"), snapshot.workCategories)
    }

    @Test
    fun `keeps unrelated jira settings when saving both fields at once`() {
        val store = FakeConfigStore(
            JiraTaskConfig(
                projectKey = "OLD",
                jira = JiraSettings(
                    baseUrl = "https://old.atlassian.net",
                    project = JiraProjectRef("10000", "예전 프로젝트"),
                ),
                policy = JiraTaskConfig.default().policy,
            ),
        )

        val saved = useCaseOf(store).save(SettingsEdit("NEW", "https://new.atlassian.net"))

        assertEquals("NEW", saved.projectKey)
        assertEquals("https://new.atlassian.net", saved.baseUrl)
        assertEquals("예전 프로젝트", saved.projectName)
        assertEquals(1, store.saveCount)
    }
}
