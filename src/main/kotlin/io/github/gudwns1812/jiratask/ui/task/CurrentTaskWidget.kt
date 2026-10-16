package io.github.gudwns1812.jiratask.ui.task

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import java.awt.Component
import java.awt.event.MouseEvent

class CurrentTaskWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    override fun ID(): String = CurrentTaskWidgetFactory.ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        CurrentTaskState.getInstance(project).refreshInBackground()
    }

    override fun dispose() = Unit

    override fun getText(): String = CurrentTaskState.getInstance(project).text()

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getTooltipText(): String = "현재 Jira 작업 번호 (클릭하면 변경)"

    override fun getClickConsumer(): com.intellij.util.Consumer<MouseEvent> =
        com.intellij.util.Consumer { StartTaskAction.open(project) }
}

class CurrentTaskWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = ID

    override fun getDisplayName(): String = "Jira Task"

    override fun isAvailable(project: Project): Boolean = project.basePath != null

    override fun createWidget(project: Project): StatusBarWidget = CurrentTaskWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) = Disposer.dispose(widget)

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    companion object {
        const val ID: String = "JiraTask.CurrentTask"
    }
}
