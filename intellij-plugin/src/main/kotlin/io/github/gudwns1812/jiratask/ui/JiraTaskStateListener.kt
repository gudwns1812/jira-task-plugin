package io.github.gudwns1812.jiratask.ui

import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.messages.Topic

/**
 * Jira Task가 보여주는 상태(설정·작업 번호·브랜치)가 바뀌었음을 알리는 통지.
 * 툴 윈도우처럼 그 상태를 그리는 화면이 스스로 다시 읽게 하는 용도다.
 */
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

        /** 애플리케이션 수준 변경(설정 저장 등)은 열려 있는 모든 프로젝트에 알린다. */
        fun publishToAllProjects() {
            ProjectManager.getInstance().openProjects.forEach(::publish)
        }
    }
}

/**
 * 결과를 알림으로 보여주고 화면 갱신까지 함께 알린다. 알림만 띄우고 갱신을 잊는 실수를 막기 위해
 * 액션들은 이 함수를 쓴다.
 */
fun notifyAndRefresh(project: Project?, type: NotificationType, message: String) {
    JiraTaskNotifications.notify(project, message, type)
    project?.let { JiraTaskStateListener.publish(it) }
}
