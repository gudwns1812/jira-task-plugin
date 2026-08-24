package io.github.gudwns1812.jiratask.ui.toolwindow

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

    fun `test draws the status it is given`() {
        val panel = JiraTaskToolWindowPanel(project)

        panel.show(
            JiraTaskStatus.of(
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
            ),
        )

        val texts = textsOf(panel.component)
        assertTrue(texts.contains("S15P11A108"))
        assertTrue(texts.contains("https://team.atlassian.net"))
        assertTrue(texts.contains("257"))
        assertTrue(texts.contains("feat/100-signup"))
        assertTrue(panel.currentStatus().isConfigured)
    }

    fun `test shows the setup guidance when nothing is configured`() {
        val panel = JiraTaskToolWindowPanel(project)

        panel.show(JiraTaskStatus.of(SettingsLoad.Loaded(SettingsSnapshot.empty()), null, null))

        val texts = textsOf(panel.component)
        assertTrue(texts.contains(JiraTaskStatus.NEEDS_SETUP))
        assertTrue(texts.contains(JiraTaskStatus.NO_TASK))
    }

    fun `test offers a button for every action`() {
        val panel = JiraTaskToolWindowPanel(project)

        val labels = buttonsOf(panel.component).map { it.text }

        assertEquals(
            listOf("이슈 초안", "브랜치 만들기", "작업 시작", "커밋", "설정 마법사", "새로 고침"),
            labels,
        )
    }

    fun `test reads the current task number from the cache`() {
        CurrentTaskState.getInstance(project).apply("257")
        val panel = JiraTaskToolWindowPanel(project)

        val status = panel.readStatus()

        assertEquals("257", status.taskNumber)
        assertEquals("테스트 픽스처 프로젝트는 git 저장소가 아니다", null, status.branch)
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
