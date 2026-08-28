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

/** [AdvanceTaskResult]를 알림 한 줄로 옮긴다. */
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
        // git 작업은 이미 끝났으므로 오류가 아니라 경고로 알린다.
        is AdvanceTaskResult.Failure -> TaskReport(
            NotificationType.WARNING,
            "작업 상태를 바꾸지 못했습니다: ${result.message}",
        )
    }
}

/**
 * 현재 작업 번호의 Jira 작업 이슈를 `진행 중`으로 옮긴다. 담당자가 작업을 고른 직후에 부른다.
 *
 * 여기서 옮기는 이유는 커밋 시점이 늦기 때문이다(RULE.md 4.2). automation에도 커밋을 계기로 하는
 * 같은 전환이 있어 두 경로가 겹치지만, 이미 목표 상태면 아무 일도 하지 않으므로 충돌하지 않는다.
 *
 * 작업 번호 저장은 이 시점에 이미 끝나 있다. 그래서 설정이 없거나 Jira가 실패해도 되돌리지 않고
 * 알림 한 줄로 알린다 — 선택을 실패로 보이게 만들면 사용자가 더 헷갈린다.
 */
object AdvanceCurrentTask {

    /** 백그라운드에서 전환한다. EDT에서 불러도 안전하다. */
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

    /** 전환하고 결과를 알린다. 호출한 쪽이 백그라운드 스레드를 보장해야 한다. */
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
