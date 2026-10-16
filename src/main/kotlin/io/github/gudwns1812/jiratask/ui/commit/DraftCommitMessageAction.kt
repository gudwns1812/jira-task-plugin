package io.github.gudwns1812.jiratask.ui.commit

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.vcs.commit.CommitMessageUi
import io.github.gudwns1812.jiratask.application.CommitDraftResult
import io.github.gudwns1812.jiratask.application.DraftCommitMessageUseCase
import io.github.gudwns1812.jiratask.ui.JiraTaskNotifications
import io.github.gudwns1812.jiratask.ui.JiraTaskSettings
import io.github.gudwns1812.jiratask.ui.OpenJiraTaskSettingsAction
import java.io.File

class DraftCommitMessageAction @JvmOverloads constructor(
    private val settings: JiraTaskSettings = JiraTaskSettings(),
    private val drafts: CommitDrafts = CommitDrafts(settings),
    private val draft: (Project, File, String?) -> CommitDraftResult = { project, directory, hint ->
        drafts.draft(project, directory, hint)
    },
    private val background: (Project, () -> Unit) -> Unit = { project, work ->
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, PROGRESS, true) {
            override fun run(indicator: ProgressIndicator) = work()
        })
    },
    private val onEdt: (Project, () -> Unit) -> Unit = { project, work ->
        ApplicationManager.getApplication().invokeLater(Runnable(work), ModalityState.any(), project.disposed)
    },
    private val announce: (Project, String) -> Unit = { project, message ->
        JiraTaskNotifications.warning(project, message, OpenJiraTaskSettingsAction.notificationAction(project))
    },
) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val hasMessageField = event.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null
        event.presentation.isEnabledAndVisible = hasMessageField && event.project?.basePath != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val workingDirectory = project.basePath?.let(::File) ?: return
        val control = event.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return

        draftInto(project, workingDirectory, MessageField(control, currentMessageIn(event, control)))
    }

    private fun currentMessageIn(event: AnActionEvent, control: CommitMessageI): String? =
        (control as? CommitMessageUi)?.text
            ?: event.getData(VcsDataKeys.COMMIT_MESSAGE_DOCUMENT)?.text

    fun draftInto(project: Project, workingDirectory: File, target: CommitMessageTarget) {
        val hint = target.currentMessage

        CommitDrafts.saveEditedFiles()

        (target as? Loading)?.startLoading()
        background(project) {
            val result = runCatching { draft(project, workingDirectory, hint) }
                .getOrElse { CommitDraftResult.Refused(DraftCommitMessageUseCase.FAILED) }

            onEdt(project) {
                (target as? Loading)?.stopLoading()
                if (!project.isDisposed) {
                    apply(project, target, hint, result)
                }
            }
        }
    }

    private fun apply(
        project: Project,
        target: CommitMessageTarget,
        hint: String?,
        result: CommitDraftResult,
    ) = when (result) {
        is CommitDraftResult.Drafted ->
            target.setMessage(JiraCommitMessages.withDraft(hint, result.message.subject))
        is CommitDraftResult.Refused -> announce(project, result.message)
    }

    interface Loading {
        fun startLoading()
        fun stopLoading()
    }

    private class MessageField(
        private val control: CommitMessageI,
        override val currentMessage: String?,
    ) : CommitMessageTarget, Loading {

        override fun setMessage(text: String) = control.setCommitMessage(text)

        override fun startLoading() {
            (control as? CommitMessageUi)?.startLoading()
        }

        override fun stopLoading() {
            (control as? CommitMessageUi)?.stopLoading()
        }
    }

    companion object {
        const val PROGRESS: String = "AI 커밋 메시지 초안"
    }
}
