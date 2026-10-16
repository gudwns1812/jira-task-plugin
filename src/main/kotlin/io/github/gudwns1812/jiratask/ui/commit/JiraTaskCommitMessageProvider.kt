package io.github.gudwns1812.jiratask.ui.commit

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.LocalChangeList
import com.intellij.openapi.vcs.changes.ui.CommitMessageProvider

class JiraTaskCommitMessageProvider(
    private val issueKey: CommitIssueKey = CommitIssueKey(),
) : CommitMessageProvider {

    override fun getCommitMessage(changeList: LocalChangeList, project: Project): String? =
        issueKey.messageFor(project, changeList.comment)
}
