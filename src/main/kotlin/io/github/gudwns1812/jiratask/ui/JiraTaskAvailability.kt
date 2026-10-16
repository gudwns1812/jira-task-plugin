package io.github.gudwns1812.jiratask.ui

import com.intellij.openapi.actionSystem.AnActionEvent
import io.github.gudwns1812.jiratask.application.SettingsSnapshot
import io.github.gudwns1812.jiratask.application.from
import io.github.gudwns1812.jiratask.domain.policy.PolicyConfigurationError
import io.github.gudwns1812.jiratask.infrastructure.PolicyConfigRepository

data class ActionAvailability(val isEnabled: Boolean, val reason: String?) {
    companion object {
        val ENABLED: ActionAvailability = ActionAvailability(true, null)

        fun disabled(reason: String): ActionAvailability = ActionAvailability(false, reason)
    }
}

enum class JiraTaskCommand {
    START_TASK,
    COMMIT,
    SEARCH,
    MERGE_REQUEST,
}

class JiraTaskAvailability(private val repository: PolicyConfigRepository = PolicyConfigRepository()) {

    fun of(command: JiraTaskCommand, hasProject: Boolean): ActionAvailability {
        if (!hasProject) {
            return ActionAvailability.disabled(NO_PROJECT)
        }

        val config = try {
            repository.loadConfig()
        } catch (error: PolicyConfigurationError) {
            return ActionAvailability.disabled(error.message ?: BROKEN_CONFIG)
        }

        return of(command, SettingsSnapshot.from(config))
    }

    fun of(command: JiraTaskCommand, snapshot: SettingsSnapshot): ActionAvailability = when (command) {
        JiraTaskCommand.START_TASK -> ActionAvailability.ENABLED
        JiraTaskCommand.COMMIT, JiraTaskCommand.SEARCH ->
            if (snapshot.projectKey.isEmpty()) ActionAvailability.disabled(NO_PROJECT_KEY) else ActionAvailability.ENABLED
        JiraTaskCommand.MERGE_REQUEST -> ActionAvailability.ENABLED
    }

    companion object {
        const val NO_PROJECT: String = "프로젝트를 열어야 사용할 수 있습니다."
        const val NO_PROJECT_KEY: String = "Jira 프로젝트 키가 없습니다. 설정 마법사를 실행하세요."
        const val BROKEN_CONFIG: String = "저장된 Jira 설정을 읽을 수 없습니다."
    }
}

fun AnActionEvent.applyAvailability(availability: ActionAvailability) {
    presentation.isEnabled = availability.isEnabled
    availability.reason?.let { presentation.description = it }
}
