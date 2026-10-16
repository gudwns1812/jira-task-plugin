package io.github.gudwns1812.jiratask.domain.mr

class GitLabError(message: String) : Exception(message)

class GitLabAuthenticationError(message: String = "GitLab 토큰이 유효하지 않습니다.") : Exception(message)

class GitLabPermissionError(
    message: String = "GitLab 권한이 부족합니다. 토큰에 api 스코프가, 프로젝트에 Developer 이상 권한이 필요합니다.",
) : Exception(message)

class GitLabNetworkError(message: String = "GitLab 서버와 통신할 수 없습니다.") : Exception(message)

data class MergeRequestCommand(
    val projectPath: String,
    val sourceBranch: String,
    val targetBranch: String,
    val title: String,
    val description: String,
    val assigneeIds: List<Int> = emptyList(),
    val reviewerIds: List<Int> = emptyList(),
    val labels: List<String> = emptyList(),
)

data class GitLabUser(val id: Int, val username: String, val name: String = "") {

    fun label(): String = when {
        name.isBlank() || name == username -> username
        else -> "$name ($username)"
    }
}

data class GitLabIdentity(
    val username: String,
    val scopesKnown: Boolean = false,
    val canCreateMergeRequest: Boolean = false,
) {
    val lacksApiScope: Boolean get() = scopesKnown && !canCreateMergeRequest
}

data class GitLabProjectInfo(val defaultBranch: String? = null, val mergeRequestTemplate: String? = null)

data class OpenMergeRequest(val webUrl: String, val title: String)

data class CreatedMergeRequest(val webUrl: String?)

interface MergeRequestPort {
    fun verifyIdentity(): GitLabIdentity

    fun openMergeRequest(projectPath: String, sourceBranch: String): OpenMergeRequest?

    fun projectInfo(projectPath: String): GitLabProjectInfo

    fun create(command: MergeRequestCommand): CreatedMergeRequest

    fun me(): GitLabUser? = null

    fun labels(projectPath: String): List<String> = emptyList()

    fun findUsers(projectPath: String, query: String = ""): List<GitLabUser> = emptyList()

    fun searchUsers(query: String): List<GitLabUser> = emptyList()
}
