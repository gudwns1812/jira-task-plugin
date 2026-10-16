package io.github.gudwns1812.jiratask.ui.task

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import io.github.gudwns1812.jiratask.application.AdvanceTaskCommand
import io.github.gudwns1812.jiratask.application.AdvanceTaskResult
import io.github.gudwns1812.jiratask.application.AdvanceTaskUseCase
import io.github.gudwns1812.jiratask.domain.jira.TaskIssueTransitioner
import io.github.gudwns1812.jiratask.domain.task.TaskNumberError
import io.github.gudwns1812.jiratask.domain.task.TaskNumbers
import io.github.gudwns1812.jiratask.infrastructure.JiraClient
import io.github.gudwns1812.jiratask.ui.JiraTaskNotifications
import io.github.gudwns1812.jiratask.ui.JiraTaskSettings
import io.github.gudwns1812.jiratask.ui.SettingsLookup
import io.github.gudwns1812.jiratask.ui.TaskTransitionSettings
import io.github.gudwns1812.jiratask.ui.notifyAndRefresh
import io.github.gudwns1812.jiratask.ui.setup.SetupWizardLauncher

object AdvanceTaskReports {

    fun of(result: AdvanceTaskResult): TaskReport = when (result) {
        is AdvanceTaskResult.Success -> TaskReport(
            NotificationType.INFORMATION,
            "작업 상태를 '${result.statusName}'로 바꿈: ${result.taskKey}",
        )
        is AdvanceTaskResult.AlreadyThere -> TaskReport(
            NotificationType.INFORMATION,
            "작업이 이미 '${result.statusName}' 상태입니다: ${result.taskKey}",
        )
        is AdvanceTaskResult.LeftAlone -> TaskReport(
            NotificationType.INFORMATION,
            "이미 완료된 작업이라 상태를 그대로 뒀습니다: ${result.taskKey}",
        )
        is AdvanceTaskResult.Failure -> TaskReport(
            NotificationType.WARNING,
            "작업 상태를 바꾸지 못했습니다: ${result.message}",
        )
    }
}

object AdvanceCurrentTask {
    fun inBackground(
        project: Project,
        taskNumber: String,
        settings: JiraTaskSettings = JiraTaskSettings(),
        createTransitioner: (TaskTransitionSettings) -> TaskIssueTransitioner = ::jiraClientFor,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread(
            Runnable { run(project, taskNumber, settings, createTransitioner) },
        )
    }

    fun run(
        project: Project,
        taskNumber: String,
        settings: JiraTaskSettings = JiraTaskSettings(),
        createTransitioner: (TaskTransitionSettings) -> TaskIssueTransitioner = ::jiraClientFor,
    ) {
        when (val lookup = settings.forTaskTransition()) {
            is SettingsLookup.Missing -> JiraTaskNotifications.warning(
                project,
                "작업 상태를 바꾸지 않았습니다. ${lookup.message}",
                NotificationAction.createSimple("설정 시작") { SetupWizardLauncher.launch(project) },
            )
            is SettingsLookup.Ready -> {
                val report = AdvanceTaskReports.of(advance(lookup.value, taskNumber, createTransitioner))
                notifyAndRefresh(project, report.type, report.message)
            }
        }
    }

    private fun advance(
        settings: TaskTransitionSettings,
        taskNumber: String,
        createTransitioner: (TaskTransitionSettings) -> TaskIssueTransitioner,
    ): AdvanceTaskResult {
        val taskKey = try {
            TaskNumbers.createTaskKey(settings.projectKey, taskNumber)
        } catch (error: TaskNumberError) {
            return AdvanceTaskResult.Failure(1, error.message ?: "작업 번호가 올바르지 않습니다.")
        }
        return AdvanceTaskUseCase(createTransitioner(settings)).execute(
            AdvanceTaskCommand(taskKey = taskKey, taskIssueTypeId = settings.taskIssueTypeId),
        )
    }

    private fun jiraClientFor(settings: TaskTransitionSettings): TaskIssueTransitioner =
        JiraClient(settings.baseUrl, settings.email, settings.apiToken)
}
