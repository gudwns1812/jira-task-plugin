package io.github.gudwns1812.jiratask.domain.task

class TaskNumberError(message: String) : Exception(message)

object TaskNumbers {
    private val TASK_NUMBER_PATTERN = Regex("""^[1-9]\d*$""")

    fun validate(taskNumber: String) {
        if (!TASK_NUMBER_PATTERN.matches(taskNumber)) {
            throw TaskNumberError("작업 번호는 0보다 큰 정수여야 합니다.")
        }
    }

    fun createTaskKey(projectKey: String, taskNumber: String): String {
        validate(taskNumber)
        return "$projectKey-$taskNumber"
    }
}
