package io.github.gudwns1812.jiratask

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class CreateJiraIssueAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        Messages.showInfoMessage(
            event.project,
            "플러그인 뼈대입니다. 기능은 추후 추가됩니다.",
            "Jira Task",
        )
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = true
    }
}
