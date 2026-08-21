package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.branch.BranchNameError
import io.github.gudwns1812.jiratask.domain.branch.BranchNames
import io.github.gudwns1812.jiratask.domain.branch.GitRepositoryError
import io.github.gudwns1812.jiratask.domain.branch.GitRepositoryPort
import io.github.gudwns1812.jiratask.domain.jira.JiraAuthenticationError
import io.github.gudwns1812.jiratask.domain.jira.JiraNetworkError
import io.github.gudwns1812.jiratask.domain.jira.JiraRequestError
import io.github.gudwns1812.jiratask.domain.jira.StoryIssueReader

data class CreateBranchCommand(
    val projectKey: String,
    val storyNumber: String,
    val name: String,
    val storyIssueTypeId: String,
    val inProgressStatusId: String,
)

sealed class CreateBranchResult {
    data class Success(val branchName: String, val storyKey: String, val transitioned: Boolean) : CreateBranchResult()
    data class PartialSuccess(val branchName: String, val storyKey: String, val message: String) : CreateBranchResult()
    data class Failure(val exitCode: Int, val message: String) : CreateBranchResult()
}

class CreateBranchUseCase(
    private val storyIssueReader: StoryIssueReader,
    private val gitRepository: GitRepositoryPort,
) {
    fun execute(command: CreateBranchCommand): CreateBranchResult {
        val storyKey: String
        val branchName: String
        try {
            storyKey = BranchNames.createStoryKey(command.projectKey, command.storyNumber)
            branchName = BranchNames.createBranchName(command.storyNumber, command.name)
        } catch (error: BranchNameError) {
            return CreateBranchResult.Failure(1, messageOf(error))
        }

        val issue = try {
            storyIssueReader.getIssue(storyKey)
        } catch (error: Exception) {
            return toPreBranchJiraFailure(error)
        }

        if (issue.issueTypeId != command.storyIssueTypeId) {
            return CreateBranchResult.Failure(1, "$storyKey 이슈는 스토리 타입이 아닙니다. (실제 타입: ${issue.issueTypeName})")
        }

        try {
            gitRepository.ensureRepository()
            gitRepository.ensureCleanWorkingTree()
            gitRepository.ensureBranchAbsent(branchName)
            gitRepository.createAndSwitch(branchName)
        } catch (error: GitRepositoryError) {
            return CreateBranchResult.Failure(5, messageOf(error))
        }

        if (issue.statusId == command.inProgressStatusId) {
            return CreateBranchResult.Success(branchName, storyKey, transitioned = false)
        }

        return try {
            val transitionId = storyIssueReader.findTransitionId(storyKey, command.inProgressStatusId)
            if (transitionId == null) {
                CreateBranchResult.PartialSuccess(branchName, storyKey, "진행 중 상태로 전환할 수 있는 Jira 워크플로를 찾지 못했습니다.")
            } else {
                storyIssueReader.transition(storyKey, transitionId)
                CreateBranchResult.Success(branchName, storyKey, transitioned = true)
            }
        } catch (error: Exception) {
            CreateBranchResult.PartialSuccess(branchName, storyKey, "Jira 스토리 상태를 진행 중으로 바꾸지 못했습니다: ${messageOf(error)}")
        }
    }

    private fun toPreBranchJiraFailure(error: Exception): CreateBranchResult.Failure = when (error) {
        is JiraAuthenticationError -> CreateBranchResult.Failure(3, messageOf(error))
        is JiraNetworkError -> CreateBranchResult.Failure(4, messageOf(error))
        is JiraRequestError -> CreateBranchResult.Failure(1, messageOf(error))
        else -> CreateBranchResult.Failure(4, "Jira 이슈를 조회하지 못했습니다.")
    }

    private fun messageOf(error: Exception): String = error.message ?: "예상하지 못한 오류가 발생했습니다."
}
