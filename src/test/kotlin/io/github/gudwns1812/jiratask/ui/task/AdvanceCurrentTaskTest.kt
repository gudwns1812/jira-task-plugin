package io.github.gudwns1812.jiratask.ui.task

import com.intellij.notification.NotificationType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.gudwns1812.jiratask.application.AdvanceTaskResult
import io.github.gudwns1812.jiratask.application.CredentialStore
import io.github.gudwns1812.jiratask.domain.jira.JiraIssue
import io.github.gudwns1812.jiratask.domain.jira.JiraStatusCategory
import io.github.gudwns1812.jiratask.domain.jira.JiraTransition
import io.github.gudwns1812.jiratask.domain.jira.TaskIssueTransitioner
import io.github.gudwns1812.jiratask.infrastructure.JiraTaskConfigStorage
import io.github.gudwns1812.jiratask.infrastructure.PolicyConfigRepository
import io.github.gudwns1812.jiratask.ui.JiraTaskSettings
import io.github.gudwns1812.jiratask.ui.TaskTransitionSettings

/** 작업을 시작할 때 Jira 상태를 옮기는 자리. 작업 키를 어디서 가져오는지와 멱등성이 핵심이다. */
class AdvanceCurrentTaskTest : BasePlatformTestCase() {

    private object AnyToken : CredentialStore {
        override fun save(baseUrl: String, email: String, apiToken: String) = Unit
        override fun get(baseUrl: String, email: String): String = "token"
    }

    private class FakeTransitioner(
        private val currentStatusId: String = "11",
        private val currentStatusName: String = "해야 할 일",
        private val currentCategory: JiraStatusCategory = JiraStatusCategory.TO_DO,
    ) : TaskIssueTransitioner {
        val requested = mutableListOf<String>()
        val executed = mutableListOf<Pair<String, String>>()

        override fun getIssue(issueKey: String): JiraIssue {
            requested += issueKey
            return JiraIssue(issueKey, "10002", "작업", currentStatusId, currentStatusName, "제목", currentCategory)
        }

        override fun listTransitions(issueKey: String): List<JiraTransition> = listOf(
            JiraTransition("3", "21", "진행 중", JiraStatusCategory.IN_PROGRESS),
            JiraTransition("5", "31", "완료", JiraStatusCategory.DONE),
        )

        override fun transition(issueKey: String, transitionId: String) {
            executed += issueKey to transitionId
        }
    }

    private fun settings(configured: Boolean = true): JiraTaskSettings {
        val repository = PolicyConfigRepository(JiraTaskConfigStorage())
        if (configured) {
            repository.saveConfig(
                mapOf(
                    "projectKey" to "S15P11A108",
                    "jira" to mapOf(
                        "baseUrl" to "https://team.atlassian.net",
                        "email" to "gudwns1423@gmail.com",
                        "issueTypes" to mapOf("task" to mapOf("id" to "10002", "name" to "작업")),
                        "statuses" to mapOf(
                            "story" to mapOf("start" to mapOf("id" to "21", "name" to "진행 중")),
                            "task" to mapOf("done" to mapOf("id" to "31", "name" to "완료")),
                        ),
                    ),
                ),
            )
        }
        return JiraTaskSettings(repository, AnyToken) { null }
    }

    private fun advance(
        taskNumber: String = "257",
        configured: Boolean = true,
        transitioner: FakeTransitioner = FakeTransitioner(),
    ): FakeTransitioner {
        AdvanceCurrentTask.run(project, taskNumber, settings(configured)) { transitioner }
        return transitioner
    }

    fun `test moves the task to the in progress status`() {
        val transitioner = advance()

        assertEquals(listOf("S15P11A108-257"), transitioner.requested)
        assertEquals(listOf("S15P11A108-257" to "3"), transitioner.executed)
    }

    fun `test never moves the task to the done status`() {
        // 완료는 MR 병합을 계기로 Jira automation이 판단한다(RULE.md 4.2).
        val transitioner = advance()

        assertEquals(listOf("S15P11A108-257" to "3"), transitioner.executed)
        assertFalse("완료 전환 id는 쓰이지 않는다", transitioner.executed.any { it.second == "5" })
    }

    fun `test does nothing when the task is already in progress`() {
        // 플러그인과 automation이 같은 전환을 겹쳐 실행한다. 두 번째는 아무 일도 하지 않아야 한다.
        val transitioner = advance(
            transitioner = FakeTransitioner("21", "진행 중", JiraStatusCategory.IN_PROGRESS),
        )

        assertEquals(listOf("S15P11A108-257"), transitioner.requested)
        assertEmpty("이미 진행 중이면 전환하지 않는다", transitioner.executed)
    }

    fun `test does not touch jira without the settings`() {
        val transitioner = advance(configured = false)

        assertEmpty("설정이 없으면 안내만 하고 Jira를 부르지 않는다", transitioner.requested)
        assertEmpty(transitioner.executed)
    }

    fun `test does not touch jira with an invalid task number`() {
        val transitioner = advance(taskNumber = "abc")

        assertEmpty(transitioner.requested)
    }

    fun `test needs no stored status to move a task`() {
        // 어느 상태가 진행 중인지는 Jira의 카테고리가 답한다. 설정에 담지 않는다.
        val ready = settings().forTaskTransition() as io.github.gudwns1812.jiratask.ui.SettingsLookup.Ready
        val value: TaskTransitionSettings = ready.value

        assertEquals("10002", value.taskIssueTypeId)
    }

    fun `test reports each result as one notification line`() {
        assertEquals(
            NotificationType.INFORMATION,
            AdvanceTaskReports.of(AdvanceTaskResult.Success("S15P11A108-257", "완료")).type,
        )
        assertEquals(
            NotificationType.INFORMATION,
            AdvanceTaskReports.of(AdvanceTaskResult.AlreadyThere("S15P11A108-257", "완료")).type,
        )

        val failure = AdvanceTaskReports.of(AdvanceTaskResult.Failure(4, "Jira 서버와 통신할 수 없습니다."))
        assertEquals("커밋은 이미 끝났으므로 오류가 아니라 경고다", NotificationType.WARNING, failure.type)
        assertTrue(failure.message.contains("Jira 서버와 통신할 수 없습니다."))
    }
}
