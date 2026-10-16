package io.github.gudwns1812.jiratask.ui

import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.messages.Topic
import io.github.gudwns1812.jiratask.ui.navigation.JiraIssueNavigation

fun interface JiraTaskStateListener {

    fun stateChanged()

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<JiraTaskStateListener> =
            Topic(JiraTaskStateListener::class.java, Topic.BroadcastDirection.NONE)

        fun publish(project: Project) {
            if (!project.isDisposed) {
                project.messageBus.syncPublisher(TOPIC).stateChanged()
            }
        }

        fun publishToAllProjects() {
            ProjectManager.getInstance().openProjects.forEach { project ->
                JiraIssueNavigation.refresh(project)
                publish(project)
            }
        }
    }
}

fun notifyAndRefresh(project: Project?, type: NotificationType, message: String) {
    JiraTaskNotifications.notify(project, message, type)
    project?.let { JiraTaskStateListener.publish(it) }
}
