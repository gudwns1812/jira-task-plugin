package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.branch.GitRepositoryError
import io.github.gudwns1812.jiratask.domain.branch.GitRepositoryPort
import io.github.gudwns1812.jiratask.domain.commit.CommitMessages
import io.github.gudwns1812.jiratask.domain.task.TaskNumbers

data class CreateCommitCommand(val projectKey: String, val message: String)

sealed class CreateCommitResult {
    data class Success(val issueKey: String, val commitMessage: String) : CreateCommitResult()
    data class Failure(val exitCode: Int, val message: String) : CreateCommitResult()
}

class CreateCommitUseCase(private val gitRepository: GitRepositoryPort) {

    fun execute(command: CreateCommitCommand): CreateCommitResult {
        val taskNumber = try {
            gitRepository.ensureRepository()
            gitRepository.getCurrentTaskNumber()
        } catch (error: GitRepositoryError) {
            return CreateCommitResult.Failure(5, messageOf(error))
        }

        if (taskNumber == null) {
            return CreateCommitResult.Failure(1, "현재 작업 번호가 없습니다. task start --number <작업 번호>로 저장하세요.")
        }

        val issueKey: String
        val commitMessage: String
        try {
            issueKey = TaskNumbers.createTaskKey(command.projectKey, taskNumber)
            commitMessage = CommitMessages.create(issueKey, command.message)
        } catch (error: Exception) {
            // createTaskKey/create는 TaskNumberError/CommitMessageError만 던지지만,
            // CLI와 동일하게 이 단계의 어떤 실패든 입력값 오류(exit 1)로 다룬다.
            return CreateCommitResult.Failure(1, messageOf(error))
        }

        try {
            gitRepository.ensureStagedChanges()
            gitRepository.commit(commitMessage)
        } catch (error: GitRepositoryError) {
            return CreateCommitResult.Failure(5, messageOf(error))
        }

        return CreateCommitResult.Success(issueKey, commitMessage)
    }

    private fun messageOf(error: Exception): String = error.message ?: "예상하지 못한 오류가 발생했습니다."
}
