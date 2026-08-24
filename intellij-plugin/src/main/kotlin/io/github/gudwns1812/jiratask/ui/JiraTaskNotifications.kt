package io.github.gudwns1812.jiratask.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.project.Project

/**
 * CLI가 콘솔에 찍던 성공·경고·실패 문구를 IDE 알림으로 옮긴 자리다.
 * CLI의 종료 코드는 알림 심각도(정보/경고/오류)로 대응한다.
 */
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
