package io.github.gudwns1812.jiratask.infrastructure

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

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
