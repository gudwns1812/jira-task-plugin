package io.github.gudwns1812.jiratask.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.project.Project

object JiraTaskNotifications {
    const val GROUP_ID: String = "Jira Task"

    fun info(project: Project?, content: String, action: AnAction? = null) =
        notify(project, content, NotificationType.INFORMATION, action)

    fun warning(project: Project?, content: String, action: AnAction? = null) =
        notify(project, content, NotificationType.WARNING, action)

    fun error(project: Project?, content: String, action: AnAction? = null) =
        notify(project, content, NotificationType.ERROR, action)

    fun notify(project: Project?, content: String, type: NotificationType, action: AnAction? = null) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(content, type)
        action?.let { notification.addAction(it) }
        notification.notify(project)
    }
}
