package io.github.gudwns1812.jiratask.infrastructure

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 설정 원문(JSON)만 IntelliJ 영속 상태로 보관한다. 검증·병합 로직은
 * [io.github.gudwns1812.jiratask.domain.policy.PolicyConfig]가 순수 함수로 담당하고,
 * 이 클래스는 그 결과 문자열을 저장·조회하는 얇은 어댑터다.
 */
@Service(Service.Level.APP)
@State(name = "JiraTaskConfig", storages = [Storage("jira-task.xml")])
class JiraTaskConfigStorage : PersistentStateComponent<JiraTaskConfigStorage.State> {

    class State {
        var configJson: String = ""
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var configJson: String
        get() = state.configJson
        set(value) {
            state.configJson = value
        }

    companion object {
        fun getInstance(): JiraTaskConfigStorage =
            ApplicationManager.getApplication().getService(JiraTaskConfigStorage::class.java)
    }
}
