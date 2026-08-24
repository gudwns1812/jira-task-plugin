package io.github.gudwns1812.jiratask.ui.task

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import io.github.gudwns1812.jiratask.application.StartTaskResult
import io.github.gudwns1812.jiratask.application.StartTaskUseCase
import io.github.gudwns1812.jiratask.infrastructure.GitRepository
import io.github.gudwns1812.jiratask.ui.JiraTaskNotifications
import java.io.File
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/** 작업 번호를 입력받는 다이얼로그. 현재 저장된 번호를 기본값으로 보여준다. */
class StartTaskDialog(project: Project?, currentTaskNumber: String? = null) : DialogWrapper(project, false) {

    val taskNumberField: JBTextField = JBTextField(currentTaskNumber.orEmpty())

    init {
        title = "Start Jira Task"
        setOKButtonText("작업 번호 저장")
        init()
        taskNumberField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                isOKActionEnabled = values().problem() == null
            }
        })
        isOKActionEnabled = values().problem() == null
    }

    override fun createCenterPanel(): JComponent = panel {
        row("작업 번호:") { cell(taskNumberField).columns(COLUMNS_MEDIUM) }
            .rowComment("커밋 메시지에 붙일 Jira 작업 번호(0보다 큰 정수)입니다.")
    }

    override fun getPreferredFocusedComponent(): JComponent = taskNumberField

    // 다이얼로그를 띄우지 않고도 안내 문구를 확인할 수 있게 공개 범위를 넓힌다.
    public override fun doValidate(): ValidationInfo? = values().problem()?.let { ValidationInfo(it) }

    fun values(): TaskFormInput = TaskFormInput(taskNumberField.text)
}

/** CLI의 `task start`에 대응하는 액션. */
class StartTaskAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project?.basePath != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        open(project)
    }

    companion object {
        fun open(project: Project) {
            val state = CurrentTaskState.getInstance(project)
            val dialog = StartTaskDialog(project, state.taskNumber)
            if (!dialog.showAndGet()) {
                return
            }
            start(project, dialog.values().trimmed())
        }

        private fun start(project: Project, input: TaskFormInput) {
            val workingDirectory = project.basePath?.let(::File) ?: return

            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Jira 작업 번호 저장", false) {
                override fun run(indicator: ProgressIndicator) {
                    val result = StartTaskUseCase(GitRepository(workingDirectory)).execute(input.taskNumber)

                    if (result is StartTaskResult.Success) {
                        CurrentTaskState.getInstance(project).apply(result.taskNumber)
                    }

                    val report = StartTaskReports.of(result)
                    JiraTaskNotifications.notify(project, report.message, report.type)
                }
            })
        }
    }
}
