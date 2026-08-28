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

    private fun configuredBoard(
        currentTask: io.github.gudwns1812.jiratask.domain.jira.SprintTask? = null,
        sprint: io.github.gudwns1812.jiratask.ui.task.SprintTaskLoad? = null,
    ) = JiraTaskBoard(configuredStatus(), currentTask, sprint)

    private fun configuredStatus() = JiraTaskStatus.of(
        SettingsLoad.Loaded(
            SettingsSnapshot.empty().copy(
                projectKey = "S15P11A108",
                baseUrl = "https://team.atlassian.net",
                projectName = "A108 프로젝트",
                storyIssueType = "스토리",
            ),
        ),
        "257",
        "feat/100-signup",
    )

    fun `test leads with the current task, not with the settings`() {
        val task = io.github.gudwns1812.jiratask.domain.jira.SprintTask(
            "S15P11A108-257",
            "[AUTH-001 | FE] 회원가입 화면",
            "진행 중",
            io.github.gudwns1812.jiratask.domain.jira.JiraStatusCategory.IN_PROGRESS,
            inOpenSprint = true,
        )
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable)

        panel.show(configuredBoard(currentTask = task))

        val texts = textsOf(panel)
        assertTrue("번호가 아니라 이슈 키를 보여 준다", texts.contains("S15P11A108-257"))
        assertTrue("무슨 작업인지 제목으로 알 수 있다", texts.contains("[AUTH-001 | FE] 회원가입 화면"))
        assertTrue(texts.contains("진행 중"))
        assertTrue(texts.contains("feat/100-signup"))
        // 값만 떠 있으면 무엇의 값인지 알 수 없다.
        listOf("Jira 키:", "제목:", "상태:", "브랜치:").forEach {
            assertTrue("$it 라벨이 있어야 한다", texts.contains(it))
        }
        assertTrue(panel.currentStatus().isConfigured)
    }

    fun `test refreshing skips the cache instead of redrawing the same list`() {
        // 캐시를 그대로 쓰면 "새로 고침해도 그대로"가 되어 버튼이 거짓말을 한다.
        val forced = mutableListOf<Boolean>()
        val panel = JiraTaskToolWindowPanel(
            project,
            testRootDisposable,
            readSprint = { force ->
                forced += force
                io.github.gudwns1812.jiratask.ui.task.SprintTaskLoad.Loaded(emptyList())
            },
        )
        // readBoard는 설정이 갖춰졌을 때만 스프린트를 읽는다.
        val storage = io.github.gudwns1812.jiratask.infrastructure.JiraTaskConfigStorage.getInstance()
        val before = storage.configJson
        storage.configJson = """{"projectKey":"S15P11A108"}"""
        try {
            panel.readBoard()
            panel.readBoard(force = true)
        } finally {
            storage.configJson = before
        }

        assertEquals("보통은 캐시를 쓰고, 새로 고침일 때만 건너뛴다", listOf(false, true), forced)
    }

    fun `test offers a refresh where the sprint is shown`() {
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable)

        panel.show(configuredBoard())

        assertTrue(
            "제목 표시줄만으로는 찾기 어렵다",
            linksOf(panel).any { it.text == JiraTaskToolWindowPanel.REFRESH },
        )
    }

    fun `test lists the sprint so a task can be started without a dialog`() {
        val tasks = listOf(
            io.github.gudwns1812.jiratask.domain.jira.SprintTask(
                "S15P11A108-258",
                "로그인 API",
                "해야 할 일",
                io.github.gudwns1812.jiratask.domain.jira.JiraStatusCategory.TO_DO,
                inOpenSprint = true,
            ),
        )
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable)

        panel.show(
            configuredBoard(sprint = io.github.gudwns1812.jiratask.ui.task.SprintTaskLoad.Loaded(tasks)),
        )

        // 머리글 한 줄 + 작업 한 줄.
        assertEquals(2, panel.sprintList.model.size)
    }

    fun `test labels the value it cannot read yet`() {
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable)

        panel.show(configuredBoard())

        assertTrue(
            "라벨만 남고 값이 비면 더 헷갈린다",
            textsOf(panel).contains(JiraTaskToolWindowPanel.UNKNOWN_VALUE),
        )
    }

    fun `test the sprint section can be folded away`() {
        // 스프린트를 다 훑고 나면 현재 작업만 보면 되는 때가 온다.
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable)
        panel.show(configuredBoard())

        val collapsibles = collapsibleSeparatorsOf(panel)
        assertTrue("현재 스프린트가 접히는 자리여야 한다", collapsibles.contains("현재 스프린트"))
        assertTrue("설정 요약도 접힌다", collapsibles.contains("설정 요약"))
        assertEquals("작업 목록의 그룹 머리글은 접히는 자리가 아니다", 2, collapsibles.size)
    }

    /** 접을 수 있는 구역의 제목들. IntelliJ는 이것을 접이식 구분선으로 그린다. */
    private fun collapsibleSeparatorsOf(panel: java.awt.Container): List<String> {
        val found = mutableListOf<String>()
        fun walk(container: java.awt.Container) {
            if (container.javaClass.name.contains("CollapsibleTitledSeparator")) {
                found += textsOf(container).filter { it.isNotBlank() }
            }
            container.components.filterIsInstance<java.awt.Container>().forEach(::walk)
        }
        walk(panel)
        return found
    }

    fun `test says why the sprint list is empty here too`() {
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable)

        panel.show(
            configuredBoard(
                sprint = io.github.gudwns1812.jiratask.ui.task.SprintTaskLoad.Loaded(
                    emptyList(),
                    io.github.gudwns1812.jiratask.domain.jira.EmptySprintReason.NO_OPEN_SPRINT,
                ),
            ),
        )

        assertTrue(
            textsOf(panel).contains(
                io.github.gudwns1812.jiratask.domain.jira.EmptySprintReason.NO_OPEN_SPRINT.message,
            ),
        )
    }

    fun `test starting from the list does nothing when a header is selected`() {
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable)
        panel.show(
            configuredBoard(
                sprint = io.github.gudwns1812.jiratask.ui.task.SprintTaskLoad.Loaded(
                    listOf(
                        io.github.gudwns1812.jiratask.domain.jira.SprintTask(
                            "S15P11A108-258",
                            "로그인 API",
                            "해야 할 일",
                            io.github.gudwns1812.jiratask.domain.jira.JiraStatusCategory.TO_DO,
                            inOpenSprint = true,
                        ),
                    ),
                ),
            ),
        )

        panel.sprintList.selectedIndex = 0
        panel.startSelected()
    }

    fun `test guides the setup instead of listing empty rows`() {
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable)

        panel.show(JiraTaskBoard(JiraTaskStatus.of(SettingsLoad.Loaded(SettingsSnapshot.empty()), null, null)))

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

        panel.show(JiraTaskBoard(JiraTaskStatus.of(SettingsLoad.Loaded(SettingsSnapshot.empty()), null, null)))
        linksOf(panel).single { it.text == JiraTaskToolWindowPanel.RUN_WIZARD }.doClick()
        linksOf(panel).first { it.text == JiraTaskToolWindowPanel.OPEN_SETTINGS }.doClick()

        assertEquals(1, wizard)
        assertEquals(1, settings)
    }

    fun `test explains a broken configuration and offers the settings`() {
        var settings = 0
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable, {}, openSettings = { settings++ })

        panel.show(JiraTaskBoard(JiraTaskStatus.of(SettingsLoad.Broken("설정 형식이 올바르지 않습니다."), "257", "main")))

        assertEquals(JiraTaskToolWindowPanel.Shown.PROBLEM, panel.shown())
        assertTrue(textsOf(panel).contains("설정 형식이 올바르지 않습니다."))
        linksOf(panel).first { it.text == JiraTaskToolWindowPanel.OPEN_SETTINGS }.doClick()
        assertEquals(1, settings)
    }

    fun `test shows the rows again once the setup is complete`() {
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable)

        panel.show(JiraTaskBoard(JiraTaskStatus.of(SettingsLoad.Loaded(SettingsSnapshot.empty()), null, null)))
        assertEquals(JiraTaskToolWindowPanel.Shown.SETUP_NEEDED, panel.shown())

        panel.show(configuredBoard())

        assertEquals(JiraTaskToolWindowPanel.Shown.STATUS, panel.shown())
        assertTrue("현재 작업 자리가 보인다", visibleTextsOf(panel).contains("S15P11A108-257"))
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
                "JiraTask.CreateMergeRequest",
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
            boardReader = { reads += Thread.currentThread(); JiraTaskBoard.EMPTY },
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
            boardReader = { reads += Thread.currentThread(); JiraTaskBoard.EMPTY },
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
            boardReader = { reads += Thread.currentThread(); JiraTaskBoard.EMPTY },
        )

        JiraTaskStateListener.publish(project)

        assertTrue("상태 변경 통지를 받으면 다시 읽는다", waitForReads(reads, 1))
    }

    fun `test refreshes itself when the tool window is shown again`() {
        val reads = mutableListOf<Thread>()
        val panel = JiraTaskToolWindowPanel(
            project,
            testRootDisposable,
            boardReader = { reads += Thread.currentThread(); JiraTaskBoard.EMPTY },
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

        panel.show(configuredBoard())
        val projectKey = labelWith("S15P11A108", panel)
        assertTrue(
            "값은 선택·복사할 수 있어야 한다(JBLabel.setCopyable이 텍스트 컴포넌트를 붙인다)",
            projectKey.components.any { it is JTextComponent },
        )
        assertEquals(UIUtil.getLabelForeground(), projectKey.foreground)

        panel.show(JiraTaskBoard(JiraTaskStatus.of(SettingsLoad.Loaded(SettingsSnapshot.empty()), null, null)))
        val placeholder = labelWith(JiraTaskStatus.NOT_CONFIGURED, panel)
        assertEquals(NamedColorUtil.getInactiveTextColor(), placeholder.foreground)
    }

    fun `test draws keys and branches in a fixed width font`() {
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable)

        panel.show(configuredBoard())

        assertEquals(Font.MONOSPACED, labelWith("S15P11A108", panel).font.family)
        assertEquals("사람이 읽는 문구는 기본 폰트로", JBFont.label().family, labelWith("A108 프로젝트", panel).font.family)
    }

    fun `test opens the jira address in a browser`() {
        val opened = mutableListOf<String>()
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable, browse = { opened += it })

        panel.show(configuredBoard())
        val link = linksOf(panel).single { it.text == "https://team.atlassian.net" }
        assertTrue(link.isEnabled)
        link.doClick()

        assertEquals(listOf("https://team.atlassian.net"), opened)
    }

    fun `test does not offer a link when no address is configured`() {
        val opened = mutableListOf<String>()
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable, browse = { opened += it })

        panel.show(JiraTaskBoard(JiraTaskStatus.of(SettingsLoad.Loaded(SettingsSnapshot.empty()), null, null)))
        val links = linksOf(panel).filter { it.text == JiraTaskStatus.NOT_CONFIGURED }
        links.forEach { it.doClick() }

        assertTrue("Jira와 GitLab 두 자리 모두 링크 자리다", links.size >= 2)
        assertTrue("열 곳이 없으면 링크가 아니어야 한다", links.none { it.isEnabled })
        assertEmpty(opened)
    }

    fun `test never shows the email even after a refresh`() {
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable)

        panel.show(configuredBoard())

        assertTrue(textsOf(panel).none { it.contains("@") })
    }

    fun `test reads the current task number from the cache`() {
        CurrentTaskState.getInstance(project).apply("257")
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable)

        val status = panel.readBoard().status

        assertEquals("257", status.taskNumber)
        assertNull("테스트 픽스처 프로젝트는 git 저장소가 아니다", status.branch)
    }

    fun `test caches the task number it read from git`() {
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable, taskNumberFromGit = { "257" })

        val status = panel.readBoard().status

        assertEquals("257", status.taskNumber)
        assertEquals("커밋 훅은 캐시만 본다", "257", CurrentTaskState.getInstance(project).taskNumber)
    }

    fun `test keeps the cached task number over the git value`() {
        CurrentTaskState.getInstance(project).apply("257")
        val panel = JiraTaskToolWindowPanel(project, testRootDisposable, taskNumberFromGit = { "999" })

        assertEquals("257", panel.readBoard().status.taskNumber)
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
