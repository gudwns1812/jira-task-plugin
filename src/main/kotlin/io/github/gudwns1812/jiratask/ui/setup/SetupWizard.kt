package io.github.gudwns1812.jiratask.ui.setup

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import io.github.gudwns1812.jiratask.application.CredentialStore
import io.github.gudwns1812.jiratask.application.RunInitialSetupUseCase
import io.github.gudwns1812.jiratask.application.SetupCancelledError
import io.github.gudwns1812.jiratask.application.SetupConnection
import io.github.gudwns1812.jiratask.application.SetupInputError
import io.github.gudwns1812.jiratask.application.SetupPrompter
import io.github.gudwns1812.jiratask.application.SetupSelectionError
import io.github.gudwns1812.jiratask.domain.jira.JiraSetupReader
import io.github.gudwns1812.jiratask.domain.policy.PolicyConfigurationError
import io.github.gudwns1812.jiratask.domain.policy.toRawMap
import io.github.gudwns1812.jiratask.infrastructure.CredentialStorageError
import io.github.gudwns1812.jiratask.infrastructure.JiraClient
import io.github.gudwns1812.jiratask.infrastructure.PasswordSafeCredentialStore
import io.github.gudwns1812.jiratask.infrastructure.PolicyConfigRepository
import com.intellij.notification.NotificationType
import io.github.gudwns1812.jiratask.ui.JiraTaskNotifications
import io.github.gudwns1812.jiratask.ui.notifyAndRefresh

sealed interface SetupWizardResult {
    data class Completed(
        val projectKey: String,
        val storyIssueType: String,
    ) : SetupWizardResult

    data object Cancelled : SetupWizardResult

    data class Failed(val message: String) : SetupWizardResult
}

object SetupFailureMessages {
    const val FALLBACK: String = "Jira 초기 설정을 완료하지 못했습니다. Jira 주소, 인증 정보, 프로젝트 권한을 확인하세요."

    fun of(error: Throwable): String = when (error) {
        is PolicyConfigurationError, is SetupSelectionError, is CredentialStorageError, is SetupInputError ->
            error.message ?: FALLBACK
        else -> FALLBACK
    }
}

class SetupWizard(
    private val prompter: SetupPrompter,
    private val credentials: CredentialStore = PasswordSafeCredentialStore(),
    private val repository: PolicyConfigRepository = PolicyConfigRepository(),
    private val createJira: (SetupConnection) -> JiraSetupReader = { connection ->
        JiraClient(connection.baseUrl, connection.email, connection.apiToken)
    },
) {
    fun run(): SetupWizardResult = try {
        val result = RunInitialSetupUseCase(
            prompter = prompter,
            createJira = createJira,
            saveConfig = { projectKey, jira ->
                repository.saveConfig(mapOf("projectKey" to projectKey, "jira" to jira.toRawMap()))
            },
            credentials = credentials,
        ).execute()
        SetupWizardResult.Completed(result.projectKey, result.storyIssueType)
    } catch (error: SetupCancelledError) {
        SetupWizardResult.Cancelled
    } catch (error: Exception) {
        SetupWizardResult.Failed(SetupFailureMessages.of(error))
    }
}

object SetupWizardLauncher {

    fun launch(project: Project?) {
        val saved = try {
            PolicyConfigRepository().loadConfig()
        } catch (error: PolicyConfigurationError) {
            null
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Jira Task 초기 설정", true) {
            override fun run(indicator: ProgressIndicator) {
                val prompter = DialogSetupPrompter(
                    project = project,
                    initialBaseUrl = saved?.jira?.baseUrl.orEmpty(),
                    initialEmail = saved?.jira?.email.orEmpty(),
                    onProgress = { indicator.text = it },
                )
                report(project, SetupWizard(prompter).run())
            }
        })
    }

    fun report(project: Project?, result: SetupWizardResult): Unit = when (result) {
        is SetupWizardResult.Completed -> notifyAndRefresh(
            project,
            NotificationType.INFORMATION,
            "Jira Task 설정을 저장했습니다. (프로젝트 ${result.projectKey}, 스토리 ${result.storyIssueType})",
        )
        is SetupWizardResult.Cancelled -> JiraTaskNotifications.info(project, "Jira Task 초기 설정을 취소했습니다.")
        is SetupWizardResult.Failed -> JiraTaskNotifications.error(project, "설정 실패: ${result.message}")
    }
}
