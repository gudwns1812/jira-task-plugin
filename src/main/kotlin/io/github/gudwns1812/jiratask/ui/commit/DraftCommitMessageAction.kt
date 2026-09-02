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

/**
 * IDE 커밋 창의 메시지 칸을 AI가 쓴 초안으로 채운다.
 *
 * 플러그인의 [CommitMessageDialog]가 아니라 **IDE가 원래 쓰는 커밋 창**을 위한 자리다. 커밋은
 * 대부분 그쪽에서 하므로, 초안 기능이 플러그인 창에만 있으면 사실상 쓰이지 않는다.
 * plugin.xml에서 `Vcs.MessageActionGroup`에 붙어 메시지 칸 옆 툴바에 아이콘으로 나온다.
 *
 * 재료를 모으고 초안을 만드는 일은 [CommitDrafts]가 한다 — 두 커밋 창이 같은 초안을 받으려면
 * 그 자리가 하나여야 한다.
 */
class DraftCommitMessageAction @JvmOverloads constructor(
    private val settings: JiraTaskSettings = JiraTaskSettings(),
    private val drafts: CommitDrafts = CommitDrafts(settings),
    /** 초안 만들기. git과 CLI를 부르므로 [background] 안에서만 불린다. */
    private val draft: (Project, File, String?) -> CommitDraftResult = { project, directory, hint ->
        drafts.draft(project, directory, hint)
    },
    /** 오래 걸리는 일을 미루는 방법. 기본은 상태 표시줄에 진행이 보이는 백그라운드 작업이다. */
    private val background: (Project, () -> Unit) -> Unit = { project, work ->
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, PROGRESS, true) {
            override fun run(indicator: ProgressIndicator) = work()
        })
    },
    /**
     * 화면으로 돌아오는 방법. 모달리티를 반드시 [ModalityState.any]로 준다 —
     * 모달 커밋 다이얼로그에서 눌렀다면 기본 모달리티로는 창이 닫힌 뒤에야 반영된다.
     */
    private val onEdt: (Project, () -> Unit) -> Unit = { project, work ->
        ApplicationManager.getApplication().invokeLater(Runnable(work), ModalityState.any(), project.disposed)
    },
    /** 초안을 만들지 못한 이유를 알리는 방법. 커밋 창에는 문구를 놓을 자리가 없어 알림으로 말한다. */
    private val announce: (Project, String) -> Unit = { project, message ->
        JiraTaskNotifications.warning(project, message, OpenJiraTaskSettingsAction.notificationAction(project))
    },
) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /**
     * 커밋 메시지 칸이 있는 자리에서만 보인다.
     *
     * 이 액션이 하는 일은 그 칸을 채우는 것뿐이라, 칸이 없으면 눌러도 아무 일도 일어나지 않는다.
     * **AI가 꺼져 있어도 숨기지 않는다** — 눌렀을 때 "설정에서 켜세요"라고 알리는 편이,
     * 아이콘이 없어 기능의 존재조차 모르는 것보다 낫다(MR 창과 같은 판단).
     */
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

    /**
     * 지금 칸에 적혀 있는 글.
     *
     * 커밋 창이 주는 것은 쓰기만 아는 [CommitMessageI]라, 읽는 길을 두 갈래로 둔다 — 그 객체가
     * 읽기도 아는 [CommitMessageUi]이면 거기서, 아니면 같은 칸의 문서에서 읽는다.
     * **읽지 못하면 이슈 키를 지키지 못한다**(그 키를 이어 붙이는 것이 [JiraCommitMessages.withDraft]다).
     */
    private fun currentMessageIn(event: AnActionEvent, control: CommitMessageI): String? =
        (control as? CommitMessageUi)?.text
            ?: event.getData(VcsDataKeys.COMMIT_MESSAGE_DOCUMENT)?.text

    /** 초안을 만들어 [target]에 넣는다. 사용자가 쓰고 있던 글은 힌트로 함께 보낸다. */
    fun draftInto(project: Project, workingDirectory: File, target: CommitMessageTarget) {
        val hint = target.currentMessage

        // git을 읽기 전에 편집기의 변경을 파일로 내린다. 저장하지 않은 편집은 git에 보이지 않는다.
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

    /**
     * 초안을 메시지 칸에 넣는다. 거절당하면 칸은 그대로 두고 이유만 알린다 —
     * 사용자가 쓰던 글을 실패한 초안이 지우면 안 된다.
     */
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

    /** 초안을 만드는 동안 메시지 칸에 진행 표시를 띄울 수 있는 대상. */
    interface Loading {
        fun startLoading()
        fun stopLoading()
    }

    /** IDE 커밋 창의 메시지 칸을 [CommitMessageTarget]으로 감싼다. */
    private class MessageField(
        private val control: CommitMessageI,
        override val currentMessage: String?,
    ) : CommitMessageTarget, Loading {

        override fun setMessage(text: String) = control.setCommitMessage(text)

        // 커밋 창이 원래 쓰는 진행 표시다. 우리가 따로 만들지 않는다.
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
