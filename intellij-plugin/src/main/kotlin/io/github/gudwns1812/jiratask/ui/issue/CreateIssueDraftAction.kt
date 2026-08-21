package io.github.gudwns1812.jiratask.ui.issue

import com.intellij.notification.NotificationAction
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import io.github.gudwns1812.jiratask.ui.IssueDraftSettings
import io.github.gudwns1812.jiratask.ui.JiraTaskNotifications
import io.github.gudwns1812.jiratask.ui.JiraTaskSettings
import io.github.gudwns1812.jiratask.ui.SettingsLookup
import io.github.gudwns1812.jiratask.ui.setup.SetupWizardLauncher

/** CLI의 `template` + `validate` + `create --dry-run`에 대응하는 액션. */
class CreateIssueDraftAction @JvmOverloads constructor(
    private val settings: JiraTaskSettings = JiraTaskSettings(),
    private val openDialog: (Project?, IssueDraftSettings) -> Unit = { project, ready ->
        CreateIssueDraftDialog(project, ready.projectKey, ready.policy).show()
    },
) : AnAction() {

    override fun actionPerformed(event: AnActionEvent) {
        when (val lookup = settings.forIssueDraft()) {
            is SettingsLookup.Ready -> openDialog(event.project, lookup.value)
            is SettingsLookup.Missing -> JiraTaskNotifications.error(
                event.project,
                lookup.message,
                NotificationAction.createSimple("설정 시작") { SetupWizardLauncher.launch(event.project) },
            )
        }
    }
}
