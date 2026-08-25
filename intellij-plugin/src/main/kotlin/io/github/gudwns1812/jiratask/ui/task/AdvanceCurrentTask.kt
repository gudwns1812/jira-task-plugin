package io.github.gudwns1812.jiratask.ui.task

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import io.github.gudwns1812.jiratask.application.AdvanceTaskCommand
import io.github.gudwns1812.jiratask.application.AdvanceTaskResult
import io.github.gudwns1812.jiratask.application.AdvanceTaskUseCase
import io.github.gudwns1812.jiratask.domain.jira.JiraNamedValue
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

/** 작업 이슈를 옮길 곳. 작업을 시작하면 진행 중으로, 커밋하면 완료로 간다. */
enum class TaskTarget {
    IN_PROGRESS,
    DONE,
    ;

    fun statusOf(settings: TaskTransitionSettings): JiraNamedValue = when (this) {
        IN_PROGRESS -> settings.inProgressStatus
        DONE -> settings.doneStatus
    }
}

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
 * 현재 작업 번호의 Jira 작업 이슈를 다음 상태로 옮긴다. 작업 시작과 커밋 성공 뒤에 부르는 공통 자리다.
 *
 * 작업 번호 저장이나 커밋은 이 시점에 이미 끝나 있다. 그래서 설정이 없거나 Jira가 실패해도
 * 되돌리지 않고 알림 한 줄로 알린다 — 커밋을 실패로 보이게 만들면 사용자가 더 헷갈린다.
 */
object AdvanceCurrentTask {

    /** 백그라운드에서 전환한다. EDT에서 불러도 안전하다. */
    fun inBackground(
        project: Project,
        taskNumber: String,
        target: TaskTarget,
        settings: JiraTaskSettings = JiraTaskSettings(),
        createTransitioner: (TaskTransitionSettings) -> TaskIssueTransitioner = ::jiraClientFor,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread(
            Runnable { run(project, taskNumber, target, settings, createTransitioner) },
        )
    }

    /** 전환하고 결과를 알린다. 호출한 쪽이 백그라운드 스레드를 보장해야 한다. */
    fun run(
        project: Project,
        taskNumber: String,
        target: TaskTarget,
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
                val report = AdvanceTaskReports.of(advance(lookup.value, taskNumber, target, createTransitioner))
                notifyAndRefresh(project, report.type, report.message)
            }
        }
    }

    private fun advance(
        settings: TaskTransitionSettings,
        taskNumber: String,
        target: TaskTarget,
        createTransitioner: (TaskTransitionSettings) -> TaskIssueTransitioner,
    ): AdvanceTaskResult {
        val taskKey = try {
            TaskNumbers.createTaskKey(settings.projectKey, taskNumber)
        } catch (error: TaskNumberError) {
            return AdvanceTaskResult.Failure(1, error.message ?: "작업 번호가 올바르지 않습니다.")
        }
        val status = target.statusOf(settings)

        return AdvanceTaskUseCase(createTransitioner(settings)).execute(
            AdvanceTaskCommand(
                taskKey = taskKey,
                taskIssueTypeId = settings.taskIssueTypeId,
                targetStatusId = status.id,
                targetStatusName = status.name,
            ),
        )
    }

    private fun jiraClientFor(settings: TaskTransitionSettings): TaskIssueTransitioner =
        JiraClient(settings.baseUrl, settings.email, settings.apiToken)
}
