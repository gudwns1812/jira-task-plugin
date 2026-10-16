package io.github.gudwns1812.jiratask.ui.task

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.WindowManager
import io.github.gudwns1812.jiratask.application.StartTaskResult
import io.github.gudwns1812.jiratask.domain.commit.GitRepositoryError
import io.github.gudwns1812.jiratask.domain.task.TaskNumberError
import io.github.gudwns1812.jiratask.domain.task.TaskNumbers
import io.github.gudwns1812.jiratask.infrastructure.GitRepository
import java.io.File

data class TaskFormInput(val taskNumber: String) {

    fun problem(): String? = try {
        TaskNumbers.validate(taskNumber.trim())
        null
    } catch (error: TaskNumberError) {
        error.message
    }

    fun trimmed(): TaskFormInput = TaskFormInput(taskNumber.trim())
}

object CurrentTaskText {
    const val NONE: String = "Jira: 작업 없음"

    fun of(taskNumber: String?): String = if (taskNumber.isNullOrBlank()) NONE else "Jira: $taskNumber"
}

data class TaskReport(val type: NotificationType, val message: String)

object StartTaskReports {
    fun of(result: StartTaskResult): TaskReport = when (result) {
        is StartTaskResult.Success -> TaskReport(
            NotificationType.INFORMATION,
            "현재 작업 번호를 저장했습니다: ${result.taskNumber}",
        )
        is StartTaskResult.Failure -> TaskReport(NotificationType.ERROR, "작업 시작 실패: ${result.message}")
    }
}

@Service(Service.Level.PROJECT)
class CurrentTaskState(private val project: Project) {

    @Volatile
    var taskNumber: String? = null
        private set

    fun apply(taskNumber: String?) {
        this.taskNumber = taskNumber
        refreshWidget()
    }

    fun refresh() = apply(readFromGit())

    fun refreshInBackground() {
        ApplicationManager.getApplication().executeOnPooledThread { refresh() }
    }

    fun text(): String = CurrentTaskText.of(taskNumber)

    private fun readFromGit(): String? {
        val basePath = project.basePath ?: return null
        return try {
            GitRepository(File(basePath)).getCurrentTaskNumber()
        } catch (error: GitRepositoryError) {
            null
        }
    }

    private fun refreshWidget() {
        if (project.isDisposed) {
            return
        }
        val statusBar: StatusBar? = WindowManager.getInstance()?.getStatusBar(project)
        statusBar?.updateWidget(CurrentTaskWidgetFactory.ID)
    }

    companion object {
        fun getInstance(project: Project): CurrentTaskState = project.getService(CurrentTaskState::class.java)
    }
}
