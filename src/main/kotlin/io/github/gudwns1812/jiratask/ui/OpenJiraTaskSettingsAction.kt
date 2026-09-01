package io.github.gudwns1812.jiratask.ui

import com.intellij.notification.NotificationAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project

/**
 * **Settings | Tools | Jira Task**를 연다.
 *
 * 새 화면을 만들지 않고 있는 화면을 여는 것뿐이다. GitLab 주소·토큰에 이르는 길이 지금은
 * "설정 메뉴를 직접 열어 Tools 밑을 뒤진다" 하나뿐이라, 사용자가 실제로 막히는 자리에서
 * 곧바로 갈 수 있게 한다 — 설정 마법사는 Jira만 묻고 GitLab은 다루지 않는다.
 */
class OpenJiraTaskSettingsAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(event: AnActionEvent) = open(event.project)

    companion object {
        fun open(project: Project?) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, JiraTaskConfigurable::class.java)
        }

        /** 설정이 없어 막혔다는 알림에 붙이는 버튼. 막힌 자리에서 바로 고치러 갈 수 있게 한다. */
        fun notificationAction(project: Project?): NotificationAction =
            NotificationAction.createSimple(OPEN_SETTINGS) { open(project) }

        const val OPEN_SETTINGS: String = "설정 열기"
    }
}
