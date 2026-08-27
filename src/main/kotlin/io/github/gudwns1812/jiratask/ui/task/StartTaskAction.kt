package io.github.gudwns1812.jiratask.ui.task

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
import io.github.gudwns1812.jiratask.application.StartTaskResult
import io.github.gudwns1812.jiratask.application.StartTaskUseCase
import io.github.gudwns1812.jiratask.infrastructure.GitRepository
import io.github.gudwns1812.jiratask.ui.JiraTaskAvailability
import io.github.gudwns1812.jiratask.ui.JiraTaskCommand
import io.github.gudwns1812.jiratask.ui.JiraTaskNotifications
import io.github.gudwns1812.jiratask.ui.applyAvailability
import io.github.gudwns1812.jiratask.ui.notifyAndRefresh
import java.io.File

/** 현재 스프린트의 내 작업을 골라 커밋에 쓸 번호로 저장하는 액션. */
class StartTaskAction @JvmOverloads constructor(
    private val availability: JiraTaskAvailability = JiraTaskAvailability(),
) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.applyAvailability(availability.of(JiraTaskCommand.START_TASK, event.project?.basePath != null))
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        open(project)
    }

    companion object {
        fun open(project: Project) {
            val dialog = StartTaskDialog(project, CurrentTaskState.getInstance(project).taskNumber)
            fill(project, dialog)

            if (dialog.showAndGet()) {
                start(project, dialog.choice())
            }
        }

        /**
         * 목록을 백그라운드에서 읽어 다이얼로그에 채운다. 다이얼로그는 먼저 열려 있으므로
         * 사용자는 기다리는 동안에도 번호를 직접 칠 수 있다.
         */
        private fun fill(project: Project, dialog: StartTaskDialog) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val load = SprintTasks.getInstance(project).current()
                ApplicationManager.getApplication().invokeLater({
                    if (!project.isDisposed) {
                        when (load) {
                            is SprintTaskLoad.Loaded -> dialog.show(load.tasks, load.reason)
                            is SprintTaskLoad.NotConfigured -> dialog.showProblem(load.message)
                            is SprintTaskLoad.Failed -> dialog.showProblem(load.message)
                        }
                    }
                }, project.disposed)
            }
        }

        private fun start(project: Project, choice: StartTaskChoice) {
            val workingDirectory = project.basePath?.let(::File) ?: return

            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Jira 작업 번호 저장", false) {
                override fun run(indicator: ProgressIndicator) {
                    val result = StartTaskUseCase(GitRepository(workingDirectory)).execute(choice.taskNumber)

                    if (result is StartTaskResult.Success) {
                        CurrentTaskState.getInstance(project).apply(result.taskNumber)
                        // 곧 상태가 바뀐다. 다음에 목록을 물으면 새로 읽게 한다.
                        SprintTasks.getInstance(project).invalidate()
                    }

                    val report = StartTaskReports.of(result)
                    notifyAndRefresh(project, report.type, report.message)

                    // 번호를 저장한 다음에야 Jira를 건드린다. 전환이 실패해도 저장은 유효하다.
                    // 이미 끝난 작업은 되돌리지 않는다(RULE.md 4.3).
                    if (result is StartTaskResult.Success && choice.movesToInProgress()) {
                        AdvanceCurrentTask.run(project, result.taskNumber)
                    }
                }
            })
        }
    }
}
