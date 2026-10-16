package io.github.gudwns1812.jiratask.ui.task

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import io.github.gudwns1812.jiratask.application.StartTaskResult
import io.github.gudwns1812.jiratask.application.StartTaskUseCase
import io.github.gudwns1812.jiratask.infrastructure.GitRepository
import io.github.gudwns1812.jiratask.ui.JiraTaskAvailability
import io.github.gudwns1812.jiratask.ui.JiraTaskCommand
import io.github.gudwns1812.jiratask.ui.JiraTaskNotifications
import io.github.gudwns1812.jiratask.ui.applyAvailability
import io.github.gudwns1812.jiratask.ui.notifyAndRefresh
import java.io.File

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

        fun fill(
            project: Project,
            dialog: StartTaskDialog,
            read: () -> SprintTaskLoad = { SprintTasks.getInstance(project).current() },
            background: (() -> Unit) -> Unit = { ApplicationManager.getApplication().executeOnPooledThread(it) },
            onEdt: (() -> Unit) -> Unit = { block ->
                ApplicationManager.getApplication().invokeLater(block, DIALOG_MODALITY, project.disposed)
            },
        ) {
            background {
                val load = read()
                onEdt {
                    if (!project.isDisposed) {
                        apply(dialog, load)
                    }
                }
            }
        }

        fun apply(dialog: StartTaskDialog, load: SprintTaskLoad) = when (load) {
            is SprintTaskLoad.Loaded -> dialog.show(load.tasks, load.reason, load.otherAssigneeCount)
            is SprintTaskLoad.NotConfigured -> dialog.showProblem(load.message)
            is SprintTaskLoad.Failed -> dialog.showProblem(load.message)
        }

        val DIALOG_MODALITY: ModalityState = ModalityState.any()

        fun start(project: Project, choice: StartTaskChoice) {
            val workingDirectory = project.basePath?.let(::File) ?: return

            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Jira 작업 번호 저장", false) {
                override fun run(indicator: ProgressIndicator) {
                    val result = StartTaskUseCase(GitRepository(workingDirectory)).execute(choice.taskNumber)

                    if (result is StartTaskResult.Success) {
                        CurrentTaskState.getInstance(project).apply(result.taskNumber)
                        SprintTasks.getInstance(project).invalidate()
                    }

                    val report = StartTaskReports.of(result)
                    notifyAndRefresh(project, report.type, report.message)

                    if (result is StartTaskResult.Success && choice.movesToInProgress()) {
                        AdvanceCurrentTask.run(project, result.taskNumber)
                    }
                }
            })
        }
    }
}
