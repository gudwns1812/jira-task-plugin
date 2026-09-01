package io.github.gudwns1812.jiratask.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurableEP
import com.intellij.openapi.vcs.changes.ui.CommitMessageProvider
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.gudwns1812.jiratask.ui.commit.CommitWithJiraTaskAction
import io.github.gudwns1812.jiratask.ui.commit.JiraTaskCheckinHandlerFactory
import io.github.gudwns1812.jiratask.ui.commit.JiraTaskCommitMessageProvider
import io.github.gudwns1812.jiratask.ui.mr.CreateMergeRequestAction
import io.github.gudwns1812.jiratask.ui.search.JiraSearchAction
import io.github.gudwns1812.jiratask.ui.task.CurrentTaskWidgetFactory
import io.github.gudwns1812.jiratask.ui.task.StartTaskAction

/** plugin.xml에 선언한 것들이 실제 IDE에서 그대로 로드되는지 확인한다(T24). */
class JiraTaskPluginRegistrationTest : BasePlatformTestCase() {

    fun `test every menu action is registered with its implementation`() {
        val manager = ActionManager.getInstance()
        val expected = mapOf(
            "JiraTask.StartTask" to StartTaskAction::class.java,
            "JiraTask.Commit" to CommitWithJiraTaskAction::class.java,
            "JiraTask.CreateMergeRequest" to CreateMergeRequestAction::class.java,
            "JiraTask.Search" to JiraSearchAction::class.java,
            "JiraTask.OpenSettings" to OpenJiraTaskSettingsAction::class.java,
            "JiraTask.RunSetupWizard" to RunSetupWizardAction::class.java,
        )

        expected.forEach { (id, type) ->
            val action = manager.getAction(id)
            assertNotNull("$id 액션이 등록되어 있어야 한다", action)
            assertTrue("$id 는 ${type.simpleName} 이어야 한다", type.isInstance(action))
        }
    }

    fun `test the actions are grouped in a Jira Task menu`() {
        val group = ActionManager.getInstance().getAction("JiraTask.Menu")

        assertTrue(group is DefaultActionGroup)
        val children = (group as DefaultActionGroup).childActionsOrStubs
        assertEquals("액션 6개와 구분선 1개", 6, children.count { it !is Separator })
        assertEquals(1, children.count { it is Separator })
        assertTrue(group.isPopup)
    }

    fun `test the removed actions are gone`() {
        val manager = ActionManager.getInstance()

        // 사람이 스프린트 전에 Jira에서 이슈를 전부 만든다(RULE.md 전제 A). 도구는 만들지 않는다.
        assertNull("이슈 초안 액션은 제거돼야 한다", manager.getAction("JiraTask.CreateIssueDraft"))
        assertNull("자리표시자 액션도 남아 있으면 안 된다", manager.getAction("JiraTask.CreateIssue"))
        // 브랜치 이름에 규칙이 없다(RULE.md 5.1). 브랜치는 IDE의 기본 Git 기능으로 만든다.
        assertNull("스토리 브랜치 액션은 제거돼야 한다", manager.getAction("JiraTask.CreateStoryBranch"))
    }

    fun `test the notification group is registered`() {
        assertNotNull(NotificationGroupManager.getInstance().getNotificationGroup(JiraTaskNotifications.GROUP_ID))
    }

    fun `test the settings panel is registered under Tools`() {
        val endpoint = ExtensionPointName<ConfigurableEP<Configurable>>("com.intellij.applicationConfigurable")

        val registered = endpoint.extensionList.single { it.instanceClass == JiraTaskConfigurable::class.java.name }

        assertEquals("tools", registered.parentId)
        assertEquals("Jira Task", registered.displayName)
    }

    fun `test the status bar widget factory is registered`() {
        val registered = ExtensionPointName<StatusBarWidgetFactory>("com.intellij.statusBarWidgetFactory")
            .extensionList
            .filterIsInstance<CurrentTaskWidgetFactory>()

        assertSize(1, registered)
        assertEquals(CurrentTaskWidgetFactory.ID, registered.single().id)
    }

    fun `test the commit message provider is registered`() {
        val registered = ExtensionPointName<CommitMessageProvider>("com.intellij.vcs.commitMessageProvider")
            .extensionList
            .filterIsInstance<JiraTaskCommitMessageProvider>()

        assertSize(1, registered)
    }

    fun `test the commit message handler is registered`() {
        val registered = ExtensionPointName<CheckinHandlerFactory>("com.intellij.checkinHandlerFactory")
            .extensionList
            .filterIsInstance<JiraTaskCheckinHandlerFactory>()

        assertSize(1, registered)
    }
}
