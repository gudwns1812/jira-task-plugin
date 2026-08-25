package io.github.gudwns1812.jiratask.ui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.wm.ToolWindowEP
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.gudwns1812.jiratask.ui.JiraTaskStateListener
import io.github.gudwns1812.jiratask.application.SettingsLoad
import io.github.gudwns1812.jiratask.application.SettingsSnapshot
import io.github.gudwns1812.jiratask.application.empty
import io.github.gudwns1812.jiratask.ui.task.CurrentTaskState
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import java.awt.Container
import java.awt.Font
import javax.swing.text.JTextComponent
import javax.swing.AbstractButton
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
        if (container is AbstractButton) {
            texts += container.text.orEmpty()
        }
        container.components.filterIsInstance<Container>().forEach { texts += textsOf(it) }
        return texts
    }

    /** 지금 화면에 보이는 컴포넌트의 문구만 모은다(숨은 화면은 제외). */
    private fun visibleTextsOf(container: Container): List<String> {
        if (!container.isVisible) {
            return emptyList()
        }
        val texts = mutableListOf<String>()
        if (container is JLabel) {
            texts += container.text.orEmpty()
        }
        if (container is AbstractButton) {
            texts += container.text.orEmpty()
        }
        container.components.filterIsInstance<Container>().forEach { texts += visibleTextsOf(it) }
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

    private fun linksOf(container: Container): List<ActionLink> {
        val links = mutableListOf<ActionLink>()
        if (container is ActionLink) {
            links += container
        }
        container.components.filterIsInstance<Container>().forEach { links += linksOf(it) }
        return links
    }

    private fun labelsOf(container: Container): List<JBLabel> {
        val labels = mutableListOf<JBLabel>()
        if (container is JBLabel) {
            labels += container
        }
        container.components.filterIsInstance<Container>().forEach { labels += labelsOf(it) }
        return labels
    }

    /** 같은 안내 문구가 여러 행에 나올 수 있으므로 첫 번째 것을 본다. */
    private fun labelWith(text: String, container: Container): JBLabel =
        labelsOf(container).first { it.text == text }

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
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable)

        panel.show(configuredStatus())

        val texts = textsOf(panel)
        assertTrue(texts.contains("S15P11A108"))
        assertTrue(texts.contains("https://team.atlassian.net"))
        assertTrue(texts.contains("257"))
        assertTrue(texts.contains("feat/100-signup"))
        assertTrue(panel.currentStatus().isConfigured)
    }

    fun `test guides the setup instead of listing empty rows`() {
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable)

        panel.show(JiraTaskStatus.of(SettingsLoad.Loaded(SettingsSnapshot.empty()), null, null))

        assertEquals(JiraTaskToolWindowPanel.Shown.SETUP_NEEDED, panel.shown())
        val texts = textsOf(panel)
        assertTrue(texts.contains(JiraTaskStatus.NEEDS_SETUP))
        assertTrue(texts.contains(JiraTaskToolWindowPanel.RUN_WIZARD))
        assertTrue(texts.contains(JiraTaskToolWindowPanel.OPEN_SETTINGS))
        assertFalse("설정되지 않음 일곱 줄을 보여주지 않는다", visibleTextsOf(panel).contains(JiraTaskStatus.NOT_CONFIGURED))
    }

    fun `test the setup guidance links run the wizard and open the settings`() {
        var wizard = 0
        var settings = 0
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable, {}, launchWizard = { wizard++ }, openSettings = { settings++ })

        panel.show(JiraTaskStatus.of(SettingsLoad.Loaded(SettingsSnapshot.empty()), null, null))
        linksOf(panel).single { it.text == JiraTaskToolWindowPanel.RUN_WIZARD }.doClick()
        linksOf(panel).first { it.text == JiraTaskToolWindowPanel.OPEN_SETTINGS }.doClick()

        assertEquals(1, wizard)
        assertEquals(1, settings)
    }

    fun `test explains a broken configuration and offers the settings`() {
        var settings = 0
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable, {}, openSettings = { settings++ })

        panel.show(JiraTaskStatus.of(SettingsLoad.Broken("설정 형식이 올바르지 않습니다."), "257", "main"))

        assertEquals(JiraTaskToolWindowPanel.Shown.PROBLEM, panel.shown())
        assertTrue(textsOf(panel).contains("설정 형식이 올바르지 않습니다."))
        linksOf(panel).first { it.text == JiraTaskToolWindowPanel.OPEN_SETTINGS }.doClick()
        assertEquals(1, settings)
    }

    fun `test shows the rows again once the setup is complete`() {
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable)

        panel.show(JiraTaskStatus.of(SettingsLoad.Loaded(SettingsSnapshot.empty()), null, null))
        assertEquals(JiraTaskToolWindowPanel.Shown.SETUP_NEEDED, panel.shown())

        panel.show(configuredStatus())

        assertEquals(JiraTaskToolWindowPanel.Shown.STATUS, panel.shown())
        assertTrue(visibleTextsOf(panel).contains("S15P11A108"))
    }

    fun `test is operated by an action toolbar instead of plain buttons`() {
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable)

        assertEmpty(
            "툴 윈도우는 폼 버튼이 아니라 툴바로 조작한다(ActionLink는 JButton을 상속하므로 제외)",
            buttonsOf(panel).filterNot { it is ActionLink },
        )
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
                "JiraTask.StartTask",
                "JiraTask.Commit",
                "JiraTask.Search",
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

    fun `test the toolbar holds only the execution actions`() {
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable)
        val manager = ActionManager.getInstance()

        val children = panel.toolbarGroup().childActionsOrStubs.toList()

        assertSize(1, children)
        assertSame(
            "메뉴와 같은 액션 그룹을 그대로 쓴다",
            manager.getAction(JiraTaskToolWindowPanel.TOOLBAR_GROUP_ID),
            children.single(),
        )
    }

    fun `test the title bar offers refresh and open settings`() {
        var settings = 0
        val reads = mutableListOf<Thread>()
        val panel = JiraTaskToolWindowPanel(
            project,
            testRootDisposable,
            openSettings = { settings++ },
            statusReader = { reads += Thread.currentThread(); JiraTaskStatus.EMPTY },
        )

        val titleActions = panel.titleActions()

        assertSize(2, titleActions)
        assertEquals(AllIcons.Actions.Refresh, titleActions[0].templatePresentation.icon)
        assertEquals(AllIcons.General.Settings, titleActions[1].templatePresentation.icon)

        titleActions[1].actionPerformed(TestActionEvent.createTestEvent(titleActions[1]))
        assertEquals(1, settings)

        titleActions[0].actionPerformed(TestActionEvent.createTestEvent(titleActions[0]))
        assertTrue("새로 고침은 상태를 다시 읽는다", waitForReads(reads, 1))
    }

    fun `test never reads git on the event dispatch thread`() {
        val reads = mutableListOf<Thread>()
        val panel = JiraTaskToolWindowPanel(
            project,
            testRootDisposable,
            statusReader = { reads += Thread.currentThread(); JiraTaskStatus.EMPTY },
        )

        panel.refreshInBackground()

        assertTrue(waitForReads(reads, 1))
        assertFalse("git 조회가 EDT에서 일어나면 IDE가 멈춘다", reads.single().name.contains("AWT-EventQueue"))
    }

    fun `test refreshes itself when the state changes`() {
        val reads = mutableListOf<Thread>()
        JiraTaskToolWindowPanel(
            project,
            testRootDisposable,
            statusReader = { reads += Thread.currentThread(); JiraTaskStatus.EMPTY },
        )

        JiraTaskStateListener.publish(project)

        assertTrue("상태 변경 통지를 받으면 다시 읽는다", waitForReads(reads, 1))
    }

    fun `test refreshes itself when the tool window is shown again`() {
        val reads = mutableListOf<Thread>()
        val panel = JiraTaskToolWindowPanel(
            project,
            testRootDisposable,
            statusReader = { reads += Thread.currentThread(); JiraTaskStatus.EMPTY },
        )

        panel.onToolWindowShown("Other")
        assertFalse("다른 툴 윈도우가 열린 것에는 반응하지 않는다", waitForReads(reads, 1, timeoutMillis = 300))

        panel.onToolWindowShown(JiraTaskToolWindowPanel.TOOL_WINDOW_ID)
        assertTrue(waitForReads(reads, 1))
    }

    /** 갱신은 백그라운드 스레드에서 일어나므로 정해진 시간까지 기다린다. */
    private fun waitForReads(reads: List<Thread>, count: Int, timeoutMillis: Long = 5_000): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            synchronized(reads) {
                if (reads.size >= count) {
                    return true
                }
            }
            Thread.sleep(20)
        }
        return reads.size >= count
    }

    fun `test shows a real value as copyable text and a missing one in grey`() {
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable)

        panel.show(configuredStatus())
        val projectKey = labelWith("S15P11A108", panel)
        assertTrue(
            "값은 선택·복사할 수 있어야 한다(JBLabel.setCopyable이 텍스트 컴포넌트를 붙인다)",
            projectKey.components.any { it is JTextComponent },
        )
        assertEquals(UIUtil.getLabelForeground(), projectKey.foreground)

        panel.show(JiraTaskStatus.of(SettingsLoad.Loaded(SettingsSnapshot.empty()), null, null))
        val placeholder = labelWith(JiraTaskStatus.NOT_CONFIGURED, panel)
        assertEquals(NamedColorUtil.getInactiveTextColor(), placeholder.foreground)
    }

    fun `test draws keys and branches in a fixed width font`() {
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable)

        panel.show(configuredStatus())

        assertEquals(Font.MONOSPACED, labelWith("S15P11A108", panel).font.family)
        assertEquals(Font.MONOSPACED, labelWith("feat/100-signup", panel).font.family)
        assertEquals("사람이 읽는 문구는 기본 폰트로", JBFont.label().family, labelWith("A108 프로젝트", panel).font.family)
    }

    fun `test opens the jira address in a browser`() {
        val opened = mutableListOf<String>()
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable, browse = { opened += it })

        panel.show(configuredStatus())
        val link = linksOf(panel).single { it.text == "https://team.atlassian.net" }
        assertTrue(link.isEnabled)
        link.doClick()

        assertEquals(listOf("https://team.atlassian.net"), opened)
    }

    fun `test does not offer a link when no address is configured`() {
        val opened = mutableListOf<String>()
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable, browse = { opened += it })

        panel.show(JiraTaskStatus.of(SettingsLoad.Loaded(SettingsSnapshot.empty()), null, null))
        val link = linksOf(panel).single { it.text == JiraTaskStatus.NOT_CONFIGURED }
        link.doClick()

        assertFalse("열 곳이 없으면 링크가 아니어야 한다", link.isEnabled)
        assertEmpty(opened)
    }

    fun `test never shows the email even after a refresh`() {
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable)

        panel.show(configuredStatus())

        assertTrue(textsOf(panel).none { it.contains("@") })
    }

    fun `test reads the current task number from the cache`() {
        CurrentTaskState.getInstance(project).apply("257")
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable)

        val status = panel.readStatus()

        assertEquals("257", status.taskNumber)
        assertNull("테스트 픽스처 프로젝트는 git 저장소가 아니다", status.branch)
    }

    fun `test caches the task number it read from git`() {
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable, taskNumberFromGit = { "257" })

        val status = panel.readStatus()

        assertEquals("257", status.taskNumber)
        assertEquals("커밋 훅은 캐시만 본다", "257", CurrentTaskState.getInstance(project).taskNumber)
    }

    fun `test keeps the cached task number over the git value`() {
        CurrentTaskState.getInstance(project).apply("257")
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable, taskNumberFromGit = { "999" })

        assertEquals("257", panel.readStatus().taskNumber)
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
