package io.github.gudwns1812.jiratask.ui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.panel
import io.github.gudwns1812.jiratask.application.EditSettingsUseCase
import io.github.gudwns1812.jiratask.infrastructure.GitRepository
import io.github.gudwns1812.jiratask.infrastructure.PolicyConfigRepository
import io.github.gudwns1812.jiratask.ui.JiraTaskConfigurable
import io.github.gudwns1812.jiratask.ui.JiraTaskStateListener
import io.github.gudwns1812.jiratask.ui.setup.SetupWizardLauncher
import io.github.gudwns1812.jiratask.ui.task.CurrentTaskState
import java.io.File
import javax.swing.JComponent

/**
 * 오른쪽 사이드바의 **Jira Task** 툴 윈도우. 조작은 상단 아이콘 툴바(=액션)로 하고, 본문은
 * 저장된 설정·현재 작업 번호·현재 브랜치를 보여주는 읽기 화면이다.
 *
 * 본문은 상태에 따라 세 화면 중 하나다 — 정상이면 상태 행, 설정이 없으면 마법사를 안내하는 빈 상태,
 * 설정이 손상됐으면 오류와 설정 열기 안내. 처음 쓰는 사용자에게 "설정되지 않음" 일곱 줄을 보여주는
 * 대신 다음에 할 일을 준다.
 *
 * 툴바가 액션을 직접 실행하므로 각 액션의 `update()`가 활성/비활성과 툴팁을 결정한다.
 */
class JiraTaskToolWindowPanel @JvmOverloads constructor(
    private val project: Project,
    parentDisposable: Disposable,
    browse: (String) -> Unit = { BrowserUtil.browse(it) },
    private val launchWizard: () -> Unit = { SetupWizardLauncher.launch(project) },
    private val openSettings: () -> Unit = {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, JiraTaskConfigurable::class.java)
    },
    /** 상태를 읽는 방법. 기본은 설정 저장소와 git을 읽는 [readStatus]이고, 테스트에서 갈아 끼운다. */
    private val statusReader: (() -> JiraTaskStatus)? = null,
    /** git에서 현재 작업 번호를 읽는 방법. 기본은 로컬 git 설정이고, 테스트에서 갈아 끼운다. */
    private val taskNumberFromGit: () -> String? = {
        project.basePath?.let { runCatching { GitRepository(File(it)).getCurrentTaskNumber() }.getOrNull() }
    },
) : SimpleToolWindowPanel(true, true) {

    /** 본문에 보이는 화면. */
    enum class Shown { STATUS, SETUP_NEEDED, PROBLEM }

    private val repository = PolicyConfigRepository()

    /** 항목의 종류(일반 문구 / 링크 / 고정폭)는 고정이므로 컴포넌트도 한 번만 만든다. */
    private val rowViews: Map<String, StatusRowView> = JiraTaskStatus.EMPTY.statusRows()
        .associate { it.name to StatusRowView.of(it.kind, browse) }

    private val problemLabel = JBLabel()

    private var status: JiraTaskStatus = JiraTaskStatus.EMPTY

    private val statusPanel: JComponent = panel {
        group("현재 상태") {
            JiraTaskStatus.EMPTY.statusRows().forEach { statusRow ->
                row("${statusRow.name}:") { cell(rowViews.getValue(statusRow.name).component) }
            }
        }
    }

    private val setupNeededPanel: JComponent = panel {
        row {
            icon(AllIcons.General.Information)
            label(JiraTaskStatus.NEEDS_SETUP)
        }
        row {
            link(RUN_WIZARD) { launchWizard() }
            link(OPEN_SETTINGS) { openSettings() }
        }
    }

    private val problemPanel: JComponent = panel {
        row {
            icon(AllIcons.General.Warning)
            cell(problemLabel)
        }
        row {
            link(OPEN_SETTINGS) { openSettings() }
        }
    }

    private val body: JComponent = panel {
        row { cell(statusPanel) }
        row { cell(setupNeededPanel) }
        row { cell(problemPanel) }
    }

    init {
        toolbar = createToolbar().component
        setContent(JBScrollPane(body))
        show(JiraTaskStatus.EMPTY)
        subscribe(parentDisposable)
    }

    /**
     * 스스로 갱신하는 경로 두 가지: 액션이 성공해 상태가 바뀌었다는 통지([JiraTaskStateListener]),
     * 그리고 툴 윈도우가 다시 보이는 순간. 후자는 다른 창에서 브랜치를 바꾸고 돌아온 경우를 잡아준다.
     */
    private fun subscribe(parentDisposable: Disposable) {
        val connection = project.messageBus.connect(parentDisposable)
        connection.subscribe(JiraTaskStateListener.TOPIC, JiraTaskStateListener { refreshInBackground() })
        connection.subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(toolWindow: ToolWindow) = onToolWindowShown(toolWindow.id)
            },
        )
    }

    /** 이 툴 윈도우가 보일 때만 다시 읽는다. */
    fun onToolWindowShown(toolWindowId: String) {
        if (toolWindowId == TOOL_WINDOW_ID) {
            refreshInBackground()
        }
    }

    /** 제목 표시줄(gear 줄)에 놓을 액션들. */
    fun titleActions(): List<AnAction> = listOf(RefreshStatusAction(), OpenSettingsAction())

    /** 지금 그려진 상태. 테스트와 새로 고침 판단에 쓴다. */
    fun currentStatus(): JiraTaskStatus = status

    /** 본문에 어떤 화면이 보이는지. */
    fun shown(): Shown = when {
        setupNeededPanel.isVisible -> Shown.SETUP_NEEDED
        problemPanel.isVisible -> Shown.PROBLEM
        else -> Shown.STATUS
    }

    /** git을 읽어야 하므로 백그라운드에서 상태를 다시 만든 뒤 화면에 반영한다. */
    fun refreshInBackground() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val next = statusReader?.invoke() ?: readStatus()
            ApplicationManager.getApplication().invokeLater({ show(next) }) { project.isDisposed }
        }
    }

    /** 백그라운드에서 현재 상태를 읽는다(테스트에서는 직접 호출한다). */
    fun readStatus(): JiraTaskStatus {
        val load = EditSettingsUseCase(repository::loadConfig, repository::saveConfig).load()
        val git = project.basePath?.let { GitRepository(File(it)) }
        val state = CurrentTaskState.getInstance(project)
        // git에서 읽은 값은 캐시에도 남긴다. 커밋 훅처럼 캐시만 보는 쪽이 빈손으로 돌아가지 않게 한다.
        val taskNumber = state.taskNumber ?: taskNumberFromGit()?.also(state::apply)

        return JiraTaskStatus.of(load, taskNumber, git?.currentBranch())
    }

    fun show(next: JiraTaskStatus) {
        status = next
        next.statusRows().forEach { statusRow -> rowViews[statusRow.name]?.show(statusRow) }
        problemLabel.text = next.problem?.message.orEmpty()

        val kind = next.problem?.kind
        statusPanel.isVisible = kind == null
        setupNeededPanel.isVisible = kind == StatusProblem.Kind.NEEDS_SETUP
        problemPanel.isVisible = kind == StatusProblem.Kind.BROKEN
    }

    private fun createToolbar(): ActionToolbar {
        val toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, toolbarGroup(), true)
        toolbar.targetComponent = this
        return toolbar
    }

    /** 툴바 구성. 새로 고침·설정 열기는 제목 표시줄에 있으므로 여기에는 실행 액션만 둔다. */
    fun toolbarGroup(): DefaultActionGroup {
        val group = DefaultActionGroup()
        ActionManager.getInstance().getAction(TOOLBAR_GROUP_ID)?.let { group.add(it) }
        return group
    }

    private inner class RefreshStatusAction : DumbAwareAction(
        "새로 고침",
        "저장된 설정과 Git 상태를 다시 읽습니다",
        AllIcons.Actions.Refresh,
    ) {
        override fun actionPerformed(event: AnActionEvent) = refreshInBackground()
    }

    private inner class OpenSettingsAction : DumbAwareAction(
        "설정 열기",
        "Settings | Tools | Jira Task를 엽니다",
        AllIcons.General.Settings,
    ) {
        override fun actionPerformed(event: AnActionEvent) = openSettings()
    }

    companion object {
        const val TOOLBAR_PLACE: String = "JiraTaskToolWindow"
        const val TOOLBAR_GROUP_ID: String = "JiraTask.ToolWindowToolbar"
        const val RUN_WIZARD: String = "설정 마법사 실행"
        const val OPEN_SETTINGS: String = "설정 열기"
        const val TOOL_WINDOW_ID: String = "Jira Task"
    }
}

class JiraTaskToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JiraTaskToolWindowPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
        toolWindow.setTitleActions(panel.titleActions())
        panel.refreshInBackground()
    }

    override fun shouldBeAvailable(project: Project): Boolean = project.basePath != null
}
