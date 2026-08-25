package io.github.gudwns1812.jiratask.domain.commit

class GitRepositoryError(message: String) : Exception(message)

/**
 * 커밋과 현재 작업 번호를 다루는 데 필요한 git 동작만 모은 포트.
 *
 * 브랜치를 만들던 시절에는 `ensureCleanWorkingTree`·`ensureBranchAbsent`·`createAndSwitch`가 함께
 * 있었다. 브랜치 이름에 규칙이 없어진 뒤로(RULE.md 5.1) 브랜치는 IDE의 기본 Git 기능으로 만든다.
 *
 * 호출은 동기(블로킹)로 설계했다. IntelliJ 플랫폼에서는 액션이 이 포트를
 * `Task.Backgroundable` 등 백그라운드 스레드에서 호출해 EDT를 막지 않아야 한다.
 */
interface CommitRepositoryPort {
    fun ensureRepository()
    fun setCurrentTaskNumber(taskNumber: String)
    fun getCurrentTaskNumber(): String?
    fun ensureStagedChanges()
    fun commit(message: String)
}
