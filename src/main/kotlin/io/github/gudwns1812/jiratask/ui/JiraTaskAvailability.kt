package io.github.gudwns1812.jiratask.ui

import com.intellij.openapi.actionSystem.AnActionEvent
import io.github.gudwns1812.jiratask.application.SettingsSnapshot
import io.github.gudwns1812.jiratask.application.from
import io.github.gudwns1812.jiratask.domain.policy.PolicyConfigurationError
import io.github.gudwns1812.jiratask.infrastructure.PolicyConfigRepository

/** 툴바·메뉴에서 액션을 쓸 수 있는지와, 쓸 수 없으면 그 이유(툴팁에 보인다). */
data class ActionAvailability(val isEnabled: Boolean, val reason: String?) {
    companion object {
        val ENABLED: ActionAvailability = ActionAvailability(true, null)

        fun disabled(reason: String): ActionAvailability = ActionAvailability(false, reason)
    }
}

/** 활성 여부를 따로 판단해야 하는 명령들. */
enum class JiraTaskCommand {
    START_TASK,
    COMMIT,
    SEARCH,
}

/**
 * 액션의 `update()`에서 쓰는 가벼운 확인.
 *
 * 일부러 하지 않는 것 두 가지: **git 호출**과 **자격 증명 저장소 접근**. `update()`는 툴바가 보이는 동안
 * 반복 호출되므로(백그라운드 스레드라도) 프로세스 실행이나 OS 키체인 조회를 넣으면 IDE가 느려진다.
 * 그래서 API 토큰과 Git 저장소 여부는 여기서 보지 않고, 실행할 때 확인해 알림으로 안내한다.
 */
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
    }

    companion object {
        const val NO_PROJECT: String = "프로젝트를 열어야 사용할 수 있습니다."
        const val NO_PROJECT_KEY: String = "Jira 프로젝트 키가 없습니다. 설정 마법사를 실행하세요."
        const val BROKEN_CONFIG: String = "저장된 Jira 설정을 읽을 수 없습니다."
    }
}

/** 판단 결과를 프레젠테이션에 반영한다. 쓸 수 있을 때는 plugin.xml의 원래 설명을 그대로 둔다. */
fun AnActionEvent.applyAvailability(availability: ActionAvailability) {
    presentation.isEnabled = availability.isEnabled
    availability.reason?.let { presentation.description = it }
}
