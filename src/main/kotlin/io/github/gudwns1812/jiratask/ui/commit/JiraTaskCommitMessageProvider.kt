package io.github.gudwns1812.jiratask.ui.commit

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.LocalChangeList
import com.intellij.openapi.vcs.changes.ui.CommitMessageProvider

/**
 * 커밋 창이 열릴 때 기본 커밋 메시지를 만든다. 이슈 키를 붙이는 자리는 여기다.
 *
 * 커밋 창의 메시지는 `AbstractCommitMessagePolicy`가 정하고, 그 첫 번째 후보가 이 확장점이다
 * (변경 목록 설명이나 지난 메시지보다 앞선다). 모달 커밋 다이얼로그와 커밋 툴 윈도우가 같은
 * 정책을 쓰므로 두 UI 모두 이 경로로 채워진다 — [JiraTaskCheckinHandlerFactory]는 커밋 창이
 * 열릴 때가 아니라 커밋 워크플로가 돌 때 불리기 때문에 창을 여는 순간에는 아무 것도 하지 못했다.
 *
 * 붙일 이슈 키가 없으면 null을 돌려 플랫폼 기본값을 그대로 쓰게 한다.
 */
class JiraTaskCommitMessageProvider(
    private val issueKey: CommitIssueKey = CommitIssueKey(),
) : CommitMessageProvider {

    override fun getCommitMessage(changeList: LocalChangeList, project: Project): String? =
        issueKey.messageFor(project, changeList.comment)
}
