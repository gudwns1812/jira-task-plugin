package io.github.gudwns1812.jiratask.domain.branch

class GitRepositoryError(message: String) : Exception(message)

/** 호출은 동기(블로킹)로 설계했다 — [io.github.gudwns1812.jiratask.domain.jira.StoryIssueReader]와 동일한 이유. */
interface GitRepositoryPort {
    fun ensureRepository()
    fun ensureCleanWorkingTree()
    fun ensureBranchAbsent(branchName: String)
    fun createAndSwitch(branchName: String)
    fun setCurrentTaskNumber(taskNumber: String)
    fun getCurrentTaskNumber(): String?
    fun ensureStagedChanges()
    fun commit(message: String)
}
