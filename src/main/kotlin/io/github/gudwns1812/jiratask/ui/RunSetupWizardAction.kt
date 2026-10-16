package io.github.gudwns1812.jiratask.ui

import com.intellij.notification.NotificationAction
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.gudwns1812.jiratask.infrastructure.PolicyConfigRepository
import io.github.gudwns1812.jiratask.ui.navigation.JiraIssueNavigation
import io.github.gudwns1812.jiratask.ui.setup.SetupWizardLauncher
import io.github.gudwns1812.jiratask.ui.task.CurrentTaskState

class RunSetupWizardAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        SetupWizardLauncher.launch(event.project)
    }
}

class JiraTaskSetupStartupActivity(
    private val refreshCurrentTask: (Project) -> Unit = { CurrentTaskState.getInstance(it).refreshInBackground() },
    private val refreshIssueLinks: (Project) -> Unit = { JiraIssueNavigation.refresh(it) },
) : ProjectActivity {

    override suspend fun execute(project: Project) {
        refreshCurrentTask(project)
        refreshIssueLinks(project)

        if (!setupNeeded(PolicyConfigRepository())) {
            return
        }

        JiraTaskNotifications.info(
            project,
            "Jira Task 초기 설정이 필요합니다. 설정 마법사에서 Jira 연결과 프로젝트 기본값을 지정하세요.",
            NotificationAction.createSimple("설정 시작") { SetupWizardLauncher.launch(project) },
        )
    }

    companion object {
        fun setupNeeded(repository: PolicyConfigRepository): Boolean = !repository.hasConfig()
    }
}
