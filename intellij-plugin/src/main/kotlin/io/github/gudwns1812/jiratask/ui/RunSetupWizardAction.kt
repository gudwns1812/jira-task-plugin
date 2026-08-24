package io.github.gudwns1812.jiratask.ui

import com.intellij.notification.NotificationAction
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.gudwns1812.jiratask.infrastructure.PolicyConfigRepository
import io.github.gudwns1812.jiratask.ui.setup.SetupWizardLauncher

/** CLI의 `jira-task config setup`에 대응하는 액션. */
class RunSetupWizardAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        SetupWizardLauncher.launch(event.project)
    }
}

/**
 * 설정이 없으면 알림 하나로 마법사를 안내한다. CLI는 인자 없이 실행하면 곧바로 대화형 설정을 시작하지만,
 * IDE에서 시작 직후 모달 다이얼로그를 강제로 띄우는 것은 침습적이므로 알림의 "설정 시작" 액션으로 넘긴다.
 */
class JiraTaskSetupStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
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
