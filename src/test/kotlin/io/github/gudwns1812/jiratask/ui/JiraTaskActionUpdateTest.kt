package io.github.gudwns1812.jiratask.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.gudwns1812.jiratask.application.CredentialStore
import io.github.gudwns1812.jiratask.infrastructure.JiraTaskConfigStorage
import io.github.gudwns1812.jiratask.infrastructure.PolicyConfigRepository
import io.github.gudwns1812.jiratask.ui.branch.CreateStoryBranchAction
import io.github.gudwns1812.jiratask.ui.commit.CommitWithJiraTaskAction
import io.github.gudwns1812.jiratask.ui.commit.JiraTaskCheckinHandlerFactory
import io.github.gudwns1812.jiratask.ui.task.StartTaskAction

/** 툴바에 놓인 액션 3개가 설정 상태에 따라 켜지고 꺼지는지, 꺼진 이유가 툴팁에 들어가는지 확인한다. */
class JiraTaskActionUpdateTest : BasePlatformTestCase() {

    private object NoCredentials : CredentialStore {
        override fun save(baseUrl: String, email: String, apiToken: String) = Unit
        override fun get(baseUrl: String, email: String): String? = null
    }

    private fun repository() = PolicyConfigRepository(JiraTaskConfigStorage())

    private fun fullyConfigured() = repository().apply {
        saveConfig(
            mapOf(
                "projectKey" to "S15P11A108",
                "jira" to mapOf(
                    "baseUrl" to "https://team.atlassian.net",
                    "email" to "gudwns1423@gmail.com",
                    "issueTypes" to mapOf("story" to mapOf("id" to "10001", "name" to "스토리")),
                    "statuses" to mapOf("story" to mapOf("start" to mapOf("id" to "21", "name" to "진행 중"))),
                ),
            ),
        )
    }

    private fun actionsUsing(repository: PolicyConfigRepository): List<AnAction> {
        val settings = JiraTaskSettings(repository, NoCredentials) { null }
        val availability = JiraTaskAvailability(repository)
        return listOf(
            CreateStoryBranchAction(settings, availability),
            StartTaskAction(availability),
            CommitWithJiraTaskAction(settings, JiraTaskCheckinHandlerFactory(settings), availability),
        )
    }

    private fun update(action: AnAction, context: DataContext): AnActionEvent {
        val event = TestActionEvent.createTestEvent(action, context)
        action.update(event)
        return event
    }

    private fun projectContext(): DataContext = SimpleDataContext.getProjectContext(project)

    fun `test every action is disabled without an open project`() {
        actionsUsing(fullyConfigured()).forEach { action ->
            val event = update(action, DataContext { null })

            assertFalse("${action.javaClass.simpleName}은 프로젝트 없이 쓸 수 없다", event.presentation.isEnabled)
            assertEquals(JiraTaskAvailability.NO_PROJECT, event.presentation.description)
        }
    }

    fun `test every action is enabled once the setup is complete`() {
        actionsUsing(fullyConfigured()).forEach { action ->
            val event = update(action, projectContext())

            assertTrue("${action.javaClass.simpleName}은 설정이 끝나면 쓸 수 있다", event.presentation.isEnabled)
        }
    }

    fun `test keeps the declared description when the action is usable`() {
        val action = actionsUsing(fullyConfigured()).first { it is CommitWithJiraTaskAction }

        val event = update(action, projectContext())

        assertEquals(
            "쓸 수 있을 때는 plugin.xml의 설명을 그대로 둔다",
            action.templatePresentation.description,
            event.presentation.description,
        )
    }

    fun `test explains why each action is off before any setup`() {
        val actions = actionsUsing(repository()).associateBy { it.javaClass.simpleName }

        val commit = update(actions.getValue("CommitWithJiraTaskAction"), projectContext())
        assertFalse(commit.presentation.isEnabled)
        assertEquals(JiraTaskAvailability.NO_PROJECT_KEY, commit.presentation.description)

        val branch = update(actions.getValue("CreateStoryBranchAction"), projectContext())
        assertFalse(branch.presentation.isEnabled)
        assertTrue(branch.presentation.description!!.contains("스토리 시작 상태"))

        val startTask = update(actions.getValue("StartTaskAction"), projectContext())
        assertTrue("작업 번호 저장은 Jira 설정 없이도 된다", startTask.presentation.isEnabled)
    }

    fun `test enables the story branch action only after the wizard filled the ids`() {
        val repository = repository().apply { setProjectKey("S15P11A108") }
        val branch = actionsUsing(repository).first { it is CreateStoryBranchAction }

        val beforeWizard = update(branch, projectContext())
        assertFalse(beforeWizard.presentation.isEnabled)
        assertTrue(beforeWizard.presentation.description!!.contains("Jira 주소"))

        repository.saveConfig(
            mapOf(
                "jira" to mapOf(
                    "baseUrl" to "https://team.atlassian.net",
                    "issueTypes" to mapOf("story" to mapOf("id" to "10001", "name" to "스토리")),
                    "statuses" to mapOf("story" to mapOf("start" to mapOf("id" to "21", "name" to "진행 중"))),
                ),
            ),
        )

        assertTrue(update(branch, projectContext()).presentation.isEnabled)
    }

    fun `test still guides the user when a disabled action is invoked from elsewhere`() {
        // 툴바에서는 비활성이지만 검색·단축키로 실행될 수 있다. 그때도 예외 없이 안내만 해야 한다.
        val action = actionsUsing(repository()).first { it is CreateStoryBranchAction }

        action.actionPerformed(TestActionEvent.createTestEvent(action, projectContext()))
    }
}
