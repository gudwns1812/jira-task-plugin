package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.branch.GitRepositoryError
import io.github.gudwns1812.jiratask.domain.branch.GitRepositoryPort
import io.github.gudwns1812.jiratask.domain.task.TaskNumberError
import io.github.gudwns1812.jiratask.domain.task.TaskNumbers

sealed class StartTaskResult {
    data class Success(val taskNumber: String) : StartTaskResult()
    data class Failure(val exitCode: Int, val message: String) : StartTaskResult()
}

class StartTaskUseCase(private val gitRepository: GitRepositoryPort) {

    fun execute(taskNumber: String): StartTaskResult {
        try {
            TaskNumbers.validate(taskNumber)
        } catch (error: TaskNumberError) {
            return StartTaskResult.Failure(1, messageOf(error))
        }

        try {
            gitRepository.ensureRepository()
            gitRepository.setCurrentTaskNumber(taskNumber)
        } catch (error: GitRepositoryError) {
            return StartTaskResult.Failure(5, messageOf(error))
        }

        return StartTaskResult.Success(taskNumber)
    }

    private fun messageOf(error: Exception): String = error.message ?: "예상하지 못한 오류가 발생했습니다."
}
