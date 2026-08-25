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
import io.github.gudwns1812.jiratask.ui.branch.CreateStoryBranchAction
import io.github.gudwns1812.jiratask.ui.commit.CommitWithJiraTaskAction
import io.github.gudwns1812.jiratask.ui.commit.JiraTaskCheckinHandlerFactory
import io.github.gudwns1812.jiratask.ui.commit.JiraTaskCommitMessageProvider
import io.github.gudwns1812.jiratask.ui.issue.CreateIssueDraftAction
import io.github.gudwns1812.jiratask.ui.task.CurrentTaskWidgetFactory
import io.github.gudwns1812.jiratask.ui.task.StartTaskAction

/** plugin.xml에 선언한 것들이 실제 IDE에서 그대로 로드되는지 확인한다(T24). */
class JiraTaskPluginRegistrationTest : BasePlatformTestCase() {

    fun `test every menu action is registered with its implementation`() {
        val manager = ActionManager.getInstance()
        val expected = mapOf(
            "JiraTask.CreateIssueDraft" to CreateIssueDraftAction::class.java,
            "JiraTask.CreateStoryBranch" to CreateStoryBranchAction::class.java,
            "JiraTask.StartTask" to StartTaskAction::class.java,
            "JiraTask.Commit" to CommitWithJiraTaskAction::class.java,
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
        assertEquals("액션 5개와 구분선 1개", 5, children.count { it !is Separator })
        assertEquals(1, children.count { it is Separator })
        assertTrue(group.isPopup)
    }

    fun `test no placeholder action is left behind`() {
        assertNull(
            "자리표시자 액션은 제거돼야 한다",
            ActionManager.getInstance().getAction("JiraTask.CreateIssue"),
        )
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
