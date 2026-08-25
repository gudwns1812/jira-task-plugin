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

/** "Start Jira Task" 다이얼로그의 입력값. 검증은 도메인 규칙([TaskNumbers])을 그대로 쓴다. */
data class TaskFormInput(val taskNumber: String) {

    fun problem(): String? = try {
        TaskNumbers.validate(taskNumber.trim())
        null
    } catch (error: TaskNumberError) {
        error.message
    }

    fun trimmed(): TaskFormInput = TaskFormInput(taskNumber.trim())
}

/** 상태 표시줄에 보여줄 문구. */
object CurrentTaskText {
    const val NONE: String = "Jira: 작업 없음"

    fun of(taskNumber: String?): String = if (taskNumber.isNullOrBlank()) NONE else "Jira: $taskNumber"
}

data class TaskReport(val type: NotificationType, val message: String)

/** [StartTaskResult]를 알림 한 줄로 옮긴다. */
object StartTaskReports {
    fun of(result: StartTaskResult): TaskReport = when (result) {
        is StartTaskResult.Success -> TaskReport(
            NotificationType.INFORMATION,
            "현재 작업 번호를 저장했습니다: ${result.taskNumber}",
        )
        is StartTaskResult.Failure -> TaskReport(NotificationType.ERROR, "작업 시작 실패: ${result.message}")
    }
}

/**
 * 현재 작업 번호의 캐시. 값의 정본은 CLI와 같은 로컬 git 설정(`jira-task.current-task`)이지만,
 * 상태 표시줄이 그릴 때마다 git을 호출하면 EDT를 막으므로 읽은 값을 여기 담아둔다.
 */
@Service(Service.Level.PROJECT)
class CurrentTaskState(private val project: Project) {

    @Volatile
    var taskNumber: String? = null
        private set

    /** 저장된 값을 그대로 반영한다(작업 시작 직후처럼 이미 아는 값이 있을 때). */
    fun apply(taskNumber: String?) {
        this.taskNumber = taskNumber
        refreshWidget()
    }

    /** git 설정을 읽어 캐시를 갱신한다. 호출한 쪽이 백그라운드 스레드를 보장해야 한다. */
    fun refresh() = apply(readFromGit())

    /** 백그라운드에서 갱신한다. */
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
