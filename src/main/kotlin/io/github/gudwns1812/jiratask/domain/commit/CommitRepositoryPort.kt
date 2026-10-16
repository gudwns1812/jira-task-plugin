package io.github.gudwns1812.jiratask.domain.commit

class GitRepositoryError(message: String) : Exception(message)

interface CommitRepositoryPort {
    fun ensureRepository()
    fun setCurrentTaskNumber(taskNumber: String)
    fun getCurrentTaskNumber(): String?
    fun ensureStagedChanges()
    fun commit(message: String)
}
