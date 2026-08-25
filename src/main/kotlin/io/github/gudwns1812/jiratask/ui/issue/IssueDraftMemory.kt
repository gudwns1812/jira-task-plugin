package io.github.gudwns1812.jiratask.ui.issue

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * 지난번 이슈 입력을 프로젝트별로 기억한다. 담당자·우선순위·스프린트처럼 한 스프린트 동안
 * 거의 그대로인 값을 매번 다시 타이핑하지 않게 하려는 것뿐이므로, 이슈 내용(제목·설명)은 담지 않는다.
 */
@Service(Service.Level.PROJECT)
@State(name = "JiraTaskIssueDraft", storages = [Storage("jira-task.xml")])
class IssueDraftMemory : PersistentStateComponent<IssueDraftMemory.State> {

    class State {
        var scope: String? = null
        var area: String? = null
        var assignee: String? = null
        var priority: String? = null
        var storyPoints: Int? = null
        var sprint: String? = null
        var dueDate: String? = null
        var epicKey: String? = null
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun remembered(): RememberedInput = RememberedInput(
        scope = state.scope,
        area = state.area,
        assignee = state.assignee,
        priority = state.priority,
        storyPoints = state.storyPoints,
        sprint = state.sprint,
        dueDate = state.dueDate,
        epicKey = state.epicKey,
    )

    /** 다음에 열 때 쓰도록 기억한다. 빈 값은 덮어쓰지 않는다. */
    fun remember(input: RememberedInput) {
        input.scope?.ifBlankNull()?.let { state.scope = it }
        input.area?.ifBlankNull()?.let { state.area = it }
        input.assignee?.ifBlankNull()?.let { state.assignee = it }
        input.priority?.ifBlankNull()?.let { state.priority = it }
        input.storyPoints?.let { state.storyPoints = it }
        input.sprint?.ifBlankNull()?.let { state.sprint = it }
        input.dueDate?.ifBlankNull()?.let { state.dueDate = it }
        input.epicKey?.ifBlankNull()?.let { state.epicKey = it }
    }

    private fun String.ifBlankNull(): String? = trim().ifEmpty { null }

    companion object {
        fun getInstance(project: Project): IssueDraftMemory = project.getService(IssueDraftMemory::class.java)
    }
}
