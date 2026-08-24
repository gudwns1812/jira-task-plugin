package io.github.gudwns1812.jiratask.ui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.wm.ToolWindowEP
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.gudwns1812.jiratask.application.SettingsLoad
import io.github.gudwns1812.jiratask.application.SettingsSnapshot
import io.github.gudwns1812.jiratask.application.empty
import io.github.gudwns1812.jiratask.ui.task.CurrentTaskState
import java.awt.Container
import javax.swing.JButton
import javax.swing.JLabel

class JiraTaskToolWindowTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            CurrentTaskState.getInstance(project).apply(null)
        } finally {
            super.tearDown()
        }
    }

    private fun textsOf(container: Container): List<String> {
        val texts = mutableListOf<String>()
        if (container is JLabel) {
            texts += container.text.orEmpty()
        }
        container.components.filterIsInstance<Container>().forEach { texts += textsOf(it) }
        return texts
    }

    private fun buttonsOf(container: Container): List<JButton> {
        val buttons = mutableListOf<JButton>()
        if (container is JButton) {
            buttons += container
        }
        container.components.filterIsInstance<Container>().forEach { buttons += buttonsOf(it) }
        return buttons
    }

    private fun toolbarsOf(container: Container): List<ActionToolbar> {
        val toolbars = mutableListOf<ActionToolbar>()
        if (container is ActionToolbar) {
            toolbars += container
        }
        container.components.filterIsInstance<Container>().forEach { toolbars += toolbarsOf(it) }
        return toolbars
    }

    private fun configuredStatus() = JiraTaskStatus.of(
        SettingsLoad.Loaded(
            SettingsSnapshot.empty().copy(
                projectKey = "S15P11A108",
                baseUrl = "https://team.atlassian.net",
                projectName = "A108 프로젝트",
                storyIssueType = "스토리",
                storyStartStatus = "진행 중",
            ),
        ),
        "257",
        "feat/100-signup",
    )

    fun `test draws the status it is given`() {
        val panel = JiraTaskToolWindowPanel(project)

        panel.show(configuredStatus())

        val texts = textsOf(panel)
        assertTrue(texts.contains("S15P11A108"))
        assertTrue(texts.contains("https://team.atlassian.net"))
        assertTrue(texts.contains("257"))
        assertTrue(texts.contains("feat/100-signup"))
        assertTrue(panel.currentStatus().isConfigured)
    }

    fun `test shows the setup guidance when nothing is configured`() {
        val panel = JiraTaskToolWindowPanel(project)

        panel.show(JiraTaskStatus.of(SettingsLoad.Loaded(SettingsSnapshot.empty()), null, null))

        val texts = textsOf(panel)
        assertTrue(texts.contains(JiraTaskStatus.NEEDS_SETUP))
        assertTrue(texts.contains(JiraTaskStatus.NO_TASK))
    }

    fun `test is operated by an action toolbar instead of plain buttons`() {
        val panel = JiraTaskToolWindowPanel(project)

        assertEmpty("툴 윈도우는 폼 버튼이 아니라 툴바로 조작한다", buttonsOf(panel))
        val toolbars = toolbarsOf(panel)
        assertSize(1, toolbars)
        assertEquals(JiraTaskToolWindowPanel.TOOLBAR_PLACE, toolbars.single().place)
        assertNotNull("targetComponent가 없으면 플랫폼이 경고한다", toolbars.single().targetComponent)
    }

    fun `test the toolbar runs the same actions as the Tools menu`() {
        val manager = ActionManager.getInstance()
        val group = manager.getAction(JiraTaskToolWindowPanel.TOOLBAR_GROUP_ID) as DefaultActionGroup

        val ids = group.childActionsOrStubs.filter { it !is Separator }.map { manager.getId(it) }

        assertEquals(
            listOf(
                "JiraTask.CreateIssueDraft",
                "JiraTask.CreateStoryBranch",
                "JiraTask.StartTask",
                "JiraTask.Commit",
                "JiraTask.RunSetupWizard",
            ),
            ids,
        )
    }

    fun `test every toolbar action has an icon`() {
        val manager = ActionManager.getInstance()
        val group = manager.getAction(JiraTaskToolWindowPanel.TOOLBAR_GROUP_ID) as DefaultActionGroup

        // 스텁이 아니라 실제 액션의 프레젠테이션을 봐야 plugin.xml의 아이콘이 반영된다.
        val withoutIcon = group.childActionsOrStubs
            .filter { it !is Separator }
            .mapNotNull { manager.getId(it) }
            .filter { manager.getAction(it).templatePresentation.icon == null }

        assertEmpty("아이콘 없는 액션은 툴바에서 빈 칸으로 보인다", withoutIcon)
    }

    fun `test the panel adds its own refresh action to the toolbar`() {
        val panel = JiraTaskToolWindowPanel(project)
        val manager = ActionManager.getInstance()

        val children = panel.toolbarGroup().childActionsOrStubs.toList()

        assertSame(
            "메뉴와 같은 액션 그룹을 그대로 쓴다",
            manager.getAction(JiraTaskToolWindowPanel.TOOLBAR_GROUP_ID),
            children.first(),
        )
        val refresh = children.last()
        assertNull("새로 고침은 이 패널에만 있는 액션이다", manager.getId(refresh))
        assertEquals(AllIcons.Actions.Refresh, refresh.templatePresentation.icon)
    }

    fun `test reads the current task number from the cache`() {
        CurrentTaskState.getInstance(project).apply("257")
        val panel = JiraTaskToolWindowPanel(project)

        val status = panel.readStatus()

        assertEquals("257", status.taskNumber)
        assertNull("테스트 픽스처 프로젝트는 git 저장소가 아니다", status.branch)
    }

    fun `test the tool window is registered on the right side`() {
        val registered = ExtensionPointName<ToolWindowEP>("com.intellij.toolWindow")
            .extensionList
            .single { it.factoryClass == JiraTaskToolWindowFactory::class.java.name }

        assertEquals("Jira Task", registered.id)
        assertEquals("right", registered.anchor)
        assertTrue(JiraTaskToolWindowFactory().shouldBeAvailable(project))
    }
}
