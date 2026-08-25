package io.github.gudwns1812.jiratask.ui.commit

import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.gudwns1812.jiratask.application.CredentialStore
import io.github.gudwns1812.jiratask.infrastructure.JiraTaskConfigStorage
import io.github.gudwns1812.jiratask.infrastructure.PolicyConfigRepository
import io.github.gudwns1812.jiratask.ui.JiraTaskSettings
import io.github.gudwns1812.jiratask.ui.task.CurrentTaskState
import java.util.concurrent.ConcurrentHashMap

/**
 * 커밋 창에 이슈 키를 붙이는 경로. 창이 열릴 때 쓰는 [CommitIssueKey.messageFor]와
 * 커밋 직전에 쓰는 [CommitIssueKey.fill]을 함께 검증한다. 캐시가 비었을 때 git을 다시 읽는지,
 * 그래도 없으면 이유를 알리는지가 핵심이다 — 예전에는 캐시가 비면 조용히 아무 것도 하지 않았다.
 */
class CommitIssueKeyTest : BasePlatformTestCase() {

    private object NoCredentials : CredentialStore {
        override fun save(baseUrl: String, email: String, apiToken: String) = Unit
        override fun get(baseUrl: String, email: String): String? = null
    }

    /** 커밋 메시지 대신 문자열 하나를 들고 있는 대역. 몇 번 써졌는지도 센다. */
    private class RecordingTarget(private var text: String? = null) : CommitMessageTarget {
        var writes: Int = 0
            private set

        override val currentMessage: String?
            get() = text

        override fun setMessage(text: String) {
            this.text = text
            writes++
        }

        fun text(): String? = text
    }

    private val announced = mutableListOf<String>()
    private val refreshes = mutableListOf<Project>()

    override fun tearDown() {
        try {
            CurrentTaskState.getInstance(project).apply(null)
        } finally {
            super.tearDown()
        }
    }

    private fun settings(projectKey: String?): JiraTaskSettings {
        val repository = PolicyConfigRepository(JiraTaskConfigStorage())
        if (projectKey != null) {
            repository.setProjectKey(projectKey)
        }
        return JiraTaskSettings(repository, NoCredentials) { null }
    }

    /** 백그라운드와 EDT를 그 자리에서 실행해 테스트가 순서를 기다리지 않게 한다. */
    private fun issueKey(
        projectKey: String? = "S15P11A108",
        refresh: (Project) -> Unit = {},
    ) = CommitIssueKey(
        settings = settings(projectKey),
        refreshTaskNumber = { refreshes += it; refresh(it) },
        background = { it() },
        onEdt = { it() },
        announce = { _, missing -> announced += missing.message },
        announced = ConcurrentHashMap(),
    )

    fun `test builds the message from the cached task number`() {
        CurrentTaskState.getInstance(project).apply("257")

        assertEquals("S15P11A108-257 ", issueKey().messageFor(project, null))
        assertEmpty(announced)
    }

    fun `test keeps the description of the change list`() {
        CurrentTaskState.getInstance(project).apply("257")

        assertEquals("S15P11A108-257 이메일 회원가입 폼 구현", issueKey().messageFor(project, "이메일 회원가입 폼 구현"))
    }

    fun `test replaces the issue key of another task in the description`() {
        CurrentTaskState.getInstance(project).apply("257")

        assertEquals(
            "S15P11A108-257 이메일 회원가입 폼 구현",
            issueKey().messageFor(project, "S15P11A108-100 이메일 회원가입 폼 구현"),
        )
    }

    fun `test leaves the message to the platform without a task number`() {
        assertNull(issueKey().messageFor(project, null))
        assertEquals("캐시가 비면 git을 읽어야 한다", listOf(project), refreshes)
        assertEquals(listOf(IssueKeyLookup.NO_TASK_NUMBER), announced)
    }

    fun `test does not warn when git had the task number after all`() {
        val found = issueKey(refresh = { CurrentTaskState.getInstance(it).apply("257") })

        assertNull("이번 창의 메시지는 이미 만들어졌다", found.messageFor(project, null))
        assertEmpty("값이 있는데 경고를 띄우면 거짓말이 된다", announced)
        assertEquals("다음 번에는 캐시에 값이 있다", "S15P11A108-257 ", found.messageFor(project, null))
    }

    fun `test does not read git when the project key is missing`() {
        CurrentTaskState.getInstance(project).apply("257")

        assertNull(issueKey(projectKey = null).messageFor(project, null))
        assertEmpty("설정 문제는 git을 다시 읽어도 그대로다", refreshes)
        assertSize(1, announced)
    }

    fun `test fills an open commit window from the cache`() {
        CurrentTaskState.getInstance(project).apply("257")
        val target = RecordingTarget()

        issueKey().fill(target, project)

        assertEquals("S15P11A108-257 ", target.text())
    }

    fun `test fills an open commit window after reading git`() {
        val target = RecordingTarget()

        issueKey(refresh = { CurrentTaskState.getInstance(it).apply("257") }).fill(target, project)

        assertEquals("S15P11A108-257 ", target.text())
        assertEmpty(announced)
    }

    fun `test keeps the message the user is writing`() {
        CurrentTaskState.getInstance(project).apply("257")
        val target = RecordingTarget("이메일 회원가입 폼 구현")

        issueKey().fill(target, project)

        assertEquals("S15P11A108-257 이메일 회원가입 폼 구현", target.text())
    }

    fun `test leaves a message that already starts with the same issue key untouched`() {
        CurrentTaskState.getInstance(project).apply("257")
        val target = RecordingTarget("S15P11A108-257 이메일 회원가입 폼 구현")

        issueKey().fill(target, project)

        assertEquals(0, target.writes)
    }

    fun `test announces the same reason only once`() {
        val issueKey = issueKey()

        issueKey.fill(RecordingTarget(), project)
        issueKey.fill(RecordingTarget(), project)

        assertSize(1, announced)
    }

    fun `test announces again after the issue key worked once`() {
        val issueKey = issueKey()

        issueKey.fill(RecordingTarget(), project)
        CurrentTaskState.getInstance(project).apply("257")
        issueKey.fill(RecordingTarget(), project)
        CurrentTaskState.getInstance(project).apply(null)
        issueKey.fill(RecordingTarget(), project)

        assertSize(2, announced)
    }

    fun `test moves the task on a successful commit`() {
        val checkedIn = mutableListOf<Project>()
        val handler = JiraTaskCheckinHandlerFactory(issueKey()) { checkedIn += it }.handlerFor(project)

        handler.checkinSuccessful()

        assertEquals("커밋이 성공하면 작업 이슈를 옮겨야 한다", listOf(project), checkedIn)
    }

    fun `test does not move the task before the commit happens`() {
        val checkedIn = mutableListOf<Project>()
        val handler = JiraTaskCheckinHandlerFactory(issueKey()) { checkedIn += it }.handlerFor(project)

        handler.beforeCheckin()

        assertEmpty(checkedIn)
    }

    fun `test reports an invalid task number as unavailable`() {
        CurrentTaskState.getInstance(project).apply("abc")

        assertTrue(issueKey().lookup(project) is IssueKeyLookup.Unavailable)
    }
}
