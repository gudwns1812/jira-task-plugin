package io.github.gudwns1812.jiratask.ui

import com.intellij.notification.NotificationAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project

class OpenJiraTaskSettingsAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(event: AnActionEvent) = open(event.project)

    companion object {
        fun open(project: Project?) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, JiraTaskConfigurable::class.java)
        }

        fun notificationAction(project: Project?): NotificationAction =
            NotificationAction.createSimple(OPEN_SETTINGS) { open(project) }

        const val OPEN_SETTINGS: String = "설정 열기"
    }
}
