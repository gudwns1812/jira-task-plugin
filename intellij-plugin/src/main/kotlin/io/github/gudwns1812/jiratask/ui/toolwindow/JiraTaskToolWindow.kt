package io.github.gudwns1812.jiratask.ui.toolwindow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.panel
import io.github.gudwns1812.jiratask.application.EditSettingsUseCase
import io.github.gudwns1812.jiratask.infrastructure.GitRepository
import io.github.gudwns1812.jiratask.infrastructure.PolicyConfigRepository
import io.github.gudwns1812.jiratask.ui.setup.SetupWizardLauncher
import io.github.gudwns1812.jiratask.ui.task.CurrentTaskState
import java.io.File
import javax.swing.JComponent

/**
 * 오른쪽 사이드바의 **Jira Task** 툴 윈도우. 상태 표시줄 위젯이 작업 번호 하나만 보여주는 것과 달리,
 * 저장된 설정·현재 작업 번호·현재 브랜치를 함께 보여주고 각 액션을 바로 실행할 수 있게 한다.
 */
class JiraTaskToolWindowPanel(private val project: Project) {

    private val repository = PolicyConfigRepository()
    private val rowLabels: Map<String, JBLabel> = JiraTaskStatus.ROW_NAMES.associateWith { JBLabel() }
    private val problemLabel = JBLabel().apply { isVisible = false }

    private var status: JiraTaskStatus = JiraTaskStatus.EMPTY

    val component: JComponent = panel {
        group("현재 상태") {
            JiraTaskStatus.ROW_NAMES.forEach { name ->
                row("$name:") { cell(rowLabels.getValue(name)) }
            }
            row { cell(problemLabel) }
        }
        group("작업") {
            row {
                button("이슈 초안") { performAction("JiraTask.CreateIssueDraft") }
                button("브랜치 만들기") { performAction("JiraTask.CreateStoryBranch") }
            }
            row {
                button("작업 시작") { performAction("JiraTask.StartTask") }
                button("커밋") { performAction("JiraTask.Commit") }
            }
            row {
                button("설정 마법사") { SetupWizardLauncher.launch(project) }
                button("새로 고침") { refreshInBackground() }
            }
        }
    }

    /** 지금 그려진 상태. 테스트와 새로 고침 판단에 쓴다. */
    fun currentStatus(): JiraTaskStatus = status

    /** git을 읽어야 하므로 백그라운드에서 상태를 다시 만든 뒤 화면에 반영한다. */
    fun refreshInBackground() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val next = readStatus()
            ApplicationManager.getApplication().invokeLater({ show(next) }) { project.isDisposed }
        }
    }

    /** 백그라운드에서 현재 상태를 읽는다(테스트에서는 직접 호출한다). */
    fun readStatus(): JiraTaskStatus {
        val load = EditSettingsUseCase(repository::loadConfig, repository::saveConfig).load()
        val git = project.basePath?.let { GitRepository(File(it)) }
        val taskNumber = CurrentTaskState.getInstance(project).taskNumber ?: runCatching { git?.getCurrentTaskNumber() }.getOrNull()

        return JiraTaskStatus.of(load, taskNumber, git?.currentBranch())
    }

    fun show(next: JiraTaskStatus) {
        status = next
        next.rows().forEach { (name, value) -> rowLabels[name]?.text = value }
        problemLabel.text = next.problem.orEmpty()
        problemLabel.isVisible = next.problem != null
    }

    private fun performAction(actionId: String) {
        val action = ActionManager.getInstance().getAction(actionId) ?: return
        val event = AnActionEvent.createFromDataContext(
            ActionPlaces.TOOLWINDOW_CONTENT,
            null,
            SimpleDataContext.getProjectContext(project),
        )
        action.actionPerformed(event)
    }
}

class JiraTaskToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JiraTaskToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, null, false)
        toolWindow.contentManager.addContent(content)
        panel.refreshInBackground()
    }

    override fun shouldBeAvailable(project: Project): Boolean = project.basePath != null
}
