package io.github.gudwns1812.jiratask.domain.mr

class GitLabError(message: String) : Exception(message)

class GitLabAuthenticationError(message: String = "GitLab 인증 또는 권한을 확인할 수 없습니다.") : Exception(message)

class GitLabNetworkError(message: String = "GitLab 서버와 통신할 수 없습니다.") : Exception(message)

/** 만들 MR 하나. */
data class MergeRequestCommand(
    val projectPath: String,
    val sourceBranch: String,
    val targetBranch: String,
    val title: String,
    val description: String,
)

/** 이미 열려 있는 MR. 같은 브랜치로 두 번 만들지 않기 위해 먼저 본다. */
data class OpenMergeRequest(val webUrl: String, val title: String)

data class CreatedMergeRequest(val webUrl: String)

/**
 * GitLab Merge Request 경계(RULE.md 5.3).
 *
 * 만들기만 한다. 병합은 여기서 하지 않는다 — MR 병합은 작업을 `완료`로 옮기는 계기이고(RULE.md 4.2),
 * 그 판단은 리뷰어와 automation의 몫이다.
 *
 * 호출은 동기(블로킹)다. 액션은 백그라운드 스레드에서 호출해 EDT를 막지 않아야 한다.
 */
interface MergeRequestPort {

    /** [sourceBranch]에서 이미 열린 MR. 없으면 null. */
    fun openMergeRequest(projectPath: String, sourceBranch: String): OpenMergeRequest?

    /** 이 프로젝트의 기본 브랜치. 못 읽으면 null. */
    fun defaultBranch(projectPath: String): String?

    fun create(command: MergeRequestCommand): CreatedMergeRequest
}
