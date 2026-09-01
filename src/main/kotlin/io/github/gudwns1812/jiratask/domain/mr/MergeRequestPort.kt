package io.github.gudwns1812.jiratask.domain.mr

class GitLabError(message: String) : Exception(message)

/**
 * 401. 토큰 자체가 통하지 않는다 — 만료됐거나, 지워졌거나, 잘못 붙여넣었다.
 *
 * 권한 부족([GitLabPermissionError])과 나누는 이유는 고칠 곳이 다르기 때문이다.
 * 이쪽은 토큰을 다시 발급해야 하고, 저쪽은 스코프나 프로젝트 역할을 손봐야 한다.
 */
class GitLabAuthenticationError(message: String = "GitLab 토큰이 유효하지 않습니다.") : Exception(message)

/** 403. 인증은 됐는데 이 일을 할 자격이 없다 — 토큰 스코프가 좁거나 프로젝트 역할이 낮다. */
class GitLabPermissionError(
    message: String = "GitLab 권한이 부족합니다. 토큰에 api 스코프가, 프로젝트에 Developer 이상 권한이 필요합니다.",
) : Exception(message)

class GitLabNetworkError(message: String = "GitLab 서버와 통신할 수 없습니다.") : Exception(message)

/** 만들 MR 하나. */
data class MergeRequestCommand(
    val projectPath: String,
    val sourceBranch: String,
    val targetBranch: String,
    val title: String,
    val description: String,
)

/**
 * 토큰이 통하는지 확인한 결과.
 *
 * [canCreateMergeRequest]가 false면 조회는 되지만 MR 생성은 실패한다 — `read_api`만 있는 토큰이 그렇다.
 * 스코프를 읽지 못하는 GitLab도 있으므로(구버전) [scopesKnown]으로 "모른다"와 "없다"를 가른다.
 */
data class GitLabIdentity(
    val username: String,
    val scopesKnown: Boolean = false,
    val canCreateMergeRequest: Boolean = false,
) {
    /** 스코프를 읽었고 `api`가 없을 때만 경고한다. 모르는 것을 위반으로 바꾸지 않는다. */
    val lacksApiScope: Boolean get() = scopesKnown && !canCreateMergeRequest
}

/**
 * GitLab이 프로젝트에 대해 알려 주는 것 중 우리가 쓰는 것.
 *
 * 둘 다 한 번의 `GET /projects/:id`에서 나온다 — 템플릿을 위해 호출을 늘리지 않는다.
 * [mergeRequestTemplate]은 프로젝트 설정의 기본 템플릿이고, 저장소의 `Default.md`보다 우선한다.
 */
data class GitLabProjectInfo(val defaultBranch: String? = null, val mergeRequestTemplate: String? = null)

/** 이미 열려 있는 MR. 같은 브랜치로 두 번 만들지 않기 위해 먼저 본다. */
data class OpenMergeRequest(val webUrl: String, val title: String)

/**
 * 만들어진 MR. 주소를 못 읽었으면 [webUrl]이 null이다.
 *
 * 주소를 모르는 것과 만들지 못한 것은 다르다. 여기서 실패로 바꾸면 이미 GitLab에 생긴 MR을 두고
 * "만들지 못했습니다"라고 말하게 되고, 사용자는 같은 MR을 한 번 더 만들려 든다.
 */
data class CreatedMergeRequest(val webUrl: String?)

/**
 * GitLab Merge Request 경계(RULE.md 5.3).
 *
 * 만들기만 한다. 병합은 여기서 하지 않는다 — MR 병합은 작업을 `완료`로 옮기는 계기이고(RULE.md 4.2),
 * 그 판단은 리뷰어와 automation의 몫이다.
 *
 * 호출은 동기(블로킹)다. 액션은 백그라운드 스레드에서 호출해 EDT를 막지 않아야 한다.
 */
interface MergeRequestPort {

    /**
     * 토큰이 통하는지 확인한다. 통하지 않으면 던진다.
     *
     * MR 다이얼로그를 다 채운 뒤에야 토큰이 틀렸다는 걸 알게 되지 않도록, 설정 화면에서 미리 부른다.
     * Jira의 [io.github.gudwns1812.jiratask.domain.jira.JiraSetupReader.verifyIdentity]와 같은 자리다.
     */
    fun verifyIdentity(): GitLabIdentity

    /** [sourceBranch]에서 이미 열린 MR. 없으면 null. */
    fun openMergeRequest(projectPath: String, sourceBranch: String): OpenMergeRequest?

    /** 이 프로젝트에 대해 GitLab이 아는 것. 못 읽으면 빈 값 — 그것 때문에 MR을 못 만들지는 않는다. */
    fun projectInfo(projectPath: String): GitLabProjectInfo

    fun create(command: MergeRequestCommand): CreatedMergeRequest
}
