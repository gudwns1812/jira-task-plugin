package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.jira.JiraNamedValue
import io.github.gudwns1812.jiratask.domain.policy.JiraProjectRef
import io.github.gudwns1812.jiratask.domain.policy.JiraSettings
import io.github.gudwns1812.jiratask.domain.policy.AiCliSettings
import io.github.gudwns1812.jiratask.domain.policy.AiProvider
import io.github.gudwns1812.jiratask.domain.policy.AiSettings
import io.github.gudwns1812.jiratask.domain.policy.JiraTaskConfig
import io.github.gudwns1812.jiratask.domain.policy.PolicyConfigurationError
import io.github.gudwns1812.jiratask.domain.policy.default
import io.github.gudwns1812.jiratask.domain.policy.mergeWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `loads an empty snapshot when nothing is configured`() {
        val snapshot = loadedSnapshot(FakeConfigStore())

        assertEquals("", snapshot.projectKey)
        assertEquals("", snapshot.baseUrl)
        assertEquals("", snapshot.projectName)
        assertEquals("", snapshot.storyIssueType)
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
        )
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
    fun `carries only the ai values the screen actually touched`() {
        // 체크박스에는 "비워 둠"이 없다. null이 그 자리를 대신하지 않으면 아무것도 안 고쳐도 저장이 일어난다.
        assertEquals(emptyMap<String, Any?>(), SettingsEdit(" ", " ", aiEnabled = null).toConfigUpdate())
        assertEquals(
            mapOf("ai" to mapOf("enabled" to true)),
            SettingsEdit("", "", aiEnabled = true).toConfigUpdate(),
        )
        assertEquals(
            mapOf("ai" to mapOf("enabled" to true, "sendDiff" to false)),
            SettingsEdit("", "", aiEnabled = true, aiSendDiff = false).toConfigUpdate(),
        )
    }

    @Test
    fun `files the model under the ai it belongs to`() {
        // 모델 이름은 provider 사이에서 옮겨지지 않는다. 어느 CLI의 것인지 함께 실어야 한다.
        assertEquals(
            mapOf("ai" to mapOf("provider" to "claude", "claude" to mapOf("model" to "sonnet"))),
            SettingsEdit("", "", aiProvider = AiProvider.CLAUDE, aiModel = " sonnet ").toConfigUpdate(),
        )
        assertEquals(
            mapOf(
                "ai" to mapOf(
                    "enabled" to true,
                    "provider" to "codex",
                    "codex" to mapOf("model" to "gpt-5-codex", "executablePath" to "/usr/local/bin/codex"),
                ),
            ),
            SettingsEdit(
                "",
                "",
                aiEnabled = true,
                aiProvider = AiProvider.CODEX,
                aiModel = "gpt-5-codex",
                aiExecutablePath = " /usr/local/bin/codex ",
            ).toConfigUpdate(),
        )
    }

    @Test
    fun `will not file a model when it does not know whose it is`() {
        // provider를 모르는 채로 저장하면 엉뚱한 CLI의 칸에 들어간다. 그러느니 싣지 않는다.
        assertEquals(
            mapOf("ai" to mapOf("enabled" to true)),
            SettingsEdit("", "", aiEnabled = true, aiModel = "opus").toConfigUpdate(),
        )
    }

    @Test
    fun `reads the default reviewers people type in one line`() {
        val edit = SettingsEdit("", "", gitLabReviewers = " gudwns1812, @jimin  hyungjun , jimin ")

        assertEquals(listOf("gudwns1812", "jimin", "hyungjun"), edit.gitLabReviewersOrNull())
        assertNull("빈 칸은 고치지 않겠다는 뜻이다", SettingsEdit("", "", gitLabReviewers = "   ").gitLabReviewersOrNull())
    }

    @Test
    fun `saves the reviewers with the gitlab address`() {
        assertEquals(
            mapOf("gitlab" to mapOf("defaultReviewers" to listOf("jimin"))),
            SettingsEdit("", "", gitLabReviewers = "jimin").toConfigUpdate(),
        )
    }

    @Test
    fun `treats a changed ai setting as a modification`() {
        val snapshot = SettingsSnapshot.empty()

        assertFalse(SettingsEdit("", "", aiEnabled = false).changes(snapshot))
        assertTrue(SettingsEdit("", "", aiEnabled = true).changes(snapshot))
        assertTrue(SettingsEdit("", "", aiProvider = AiProvider.CLAUDE, aiModel = "opus").changes(snapshot))
        assertFalse(
            "빈 칸은 변경이 아니다",
            SettingsEdit("", "", aiProvider = AiProvider.CLAUDE, aiModel = "  ").changes(snapshot),
        )
        assertTrue("AI를 바꾸는 것도 변경이다", SettingsEdit("", "", aiProvider = AiProvider.CODEX).changes(snapshot))
        assertFalse(SettingsEdit("", "", aiProvider = AiProvider.CLAUDE).changes(snapshot))
        assertTrue("diff를 끄는 것도 변경이다", SettingsEdit("", "", aiSendDiff = false).changes(snapshot))
        assertFalse(SettingsEdit("", "", aiSendDiff = true).changes(snapshot))
    }

    @Test
    fun `compares the model with what that ai has saved, not another's`() {
        // Claude에 opus를 저장해 둔 상태에서 Codex를 골라 같은 글자를 쳐도, 그것은 Codex 쪽의 변경이다.
        val snapshot = SettingsSnapshot.from(
            JiraTaskConfig(
                ai = AiSettings(
                    enabled = true,
                    provider = AiProvider.CLAUDE,
                    clis = mapOf(AiProvider.CLAUDE to AiCliSettings(model = "opus")),
                ),
            ),
        )

        assertFalse(SettingsEdit("", "", aiProvider = AiProvider.CLAUDE, aiModel = "opus").changes(snapshot))
        assertTrue(SettingsEdit("", "", aiProvider = AiProvider.CODEX, aiModel = "opus").changes(snapshot))
    }

    @Test
    fun `keeps every ai's saved values so the screen can switch without saving`() {
        val snapshot = SettingsSnapshot.from(
            JiraTaskConfig(
                ai = AiSettings(
                    provider = AiProvider.CODEX,
                    clis = mapOf(
                        AiProvider.CLAUDE to AiCliSettings(model = "opus"),
                        AiProvider.CODEX to AiCliSettings(executablePath = "/opt/homebrew/bin/codex"),
                    ),
                ),
            ),
        )

        assertEquals("codex", snapshot.aiProvider)
        assertEquals("opus", snapshot.aiCli("claude").model)
        assertEquals("/opt/homebrew/bin/codex", snapshot.aiExecutablePath)
        assertEquals("고른 쪽의 모델은 비어 있다", "", snapshot.aiModel)
    }

    @Test
    fun `an empty snapshot has no configured value at all`() {
        val snapshot = SettingsSnapshot.empty()

        assertEquals("", snapshot.projectKey)
        assertEquals("", snapshot.baseUrl)
        assertFalse("AI는 기본값이 꺼짐이다", snapshot.aiEnabled)
        assertTrue("AI를 켰다면 커밋 초안은 변경 내용까지 본다", snapshot.aiSendDiff)
        assertEquals("", snapshot.aiModel)
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
            ),
        )

        val saved = useCaseOf(store).save(SettingsEdit("NEW", "https://new.atlassian.net"))

        assertEquals("NEW", saved.projectKey)
        assertEquals("https://new.atlassian.net", saved.baseUrl)
        assertEquals("예전 프로젝트", saved.projectName)
        assertEquals(1, store.saveCount)
    }
}
