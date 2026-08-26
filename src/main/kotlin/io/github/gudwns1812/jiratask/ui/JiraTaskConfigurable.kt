package io.github.gudwns1812.jiratask.ui

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import io.github.gudwns1812.jiratask.application.EditSettingsUseCase
import io.github.gudwns1812.jiratask.application.SettingsEdit
import io.github.gudwns1812.jiratask.application.SettingsLoad
import io.github.gudwns1812.jiratask.application.SettingsSnapshot
import io.github.gudwns1812.jiratask.application.empty
import io.github.gudwns1812.jiratask.domain.policy.PolicyConfigurationError
import io.github.gudwns1812.jiratask.infrastructure.CredentialStorageError
import io.github.gudwns1812.jiratask.infrastructure.GitLabTokenStore
import io.github.gudwns1812.jiratask.infrastructure.PolicyConfigRepository
import io.github.gudwns1812.jiratask.ui.setup.SetupWizardLauncher
import javax.swing.JComponent

/**
 * **Settings | Tools | Jira Task** 패널. CLI의 `config show`(읽기 전용 표시)와
 * `config set`(프로젝트 키·Jira 주소 저장)을 대체한다.
 *
 * 이메일과 API 토큰은 표시하지 않는다 — 토큰은 IDE PasswordSafe에만 있고, 이메일은
 * [SettingsSnapshot]에 애초에 담기지 않는다.
 */
class JiraTaskConfigurable @JvmOverloads constructor(
    private val settings: EditSettingsUseCase = persistedSettings(),
    private val gitLabTokens: GitLabTokenStore = GitLabTokenStore(),
    private val launchSetupWizard: () -> Unit = { SetupWizardLauncher.launch(null) },
) : Configurable {

    private val projectKeyField = JBTextField()
    private val baseUrlField = JBTextField()
    private val projectNameLabel = JBLabel()
    private val storyIssueTypeLabel = JBLabel()
    private val taskIssueTypeLabel = JBLabel()
    private val epicIssueTypeLabel = JBLabel()
    private val storyStartStatusLabel = JBLabel()
    private val gitLabBaseUrlField = JBTextField()
    private val gitLabTokenField = com.intellij.ui.components.JBPasswordField()
    private val problemLabel = JBLabel().apply { isVisible = false }

    private var saved: SettingsSnapshot = SettingsSnapshot.empty()

    override fun getDisplayName(): String = "Jira Task"

    override fun createComponent(): JComponent {
        val component = panel {
            row("Jira 프로젝트 키:") {
                cell(projectKeyField).columns(COLUMNS_MEDIUM)
            }.rowComment("대문자 영문으로 시작하고 대문자 영문·숫자·밑줄만 사용합니다. 비워 두면 기존 값을 유지합니다.")

            row("Jira 주소:") {
                cell(baseUrlField).columns(COLUMNS_LARGE)
            }.rowComment("예: https://your-team.atlassian.net. 비워 두면 기존 값을 유지합니다.")

            group("연결된 Jira 정보") {
                row("프로젝트:") { cell(projectNameLabel) }
                row("스토리 유형:") { cell(storyIssueTypeLabel) }
                row("작업 유형:") { cell(taskIssueTypeLabel) }
                row("에픽 유형:") { cell(epicIssueTypeLabel) }
                row("작업 시작 상태:") { cell(storyStartStatusLabel) }
                row {
                    comment("이메일과 API 토큰은 이 화면에 표시하지 않습니다. 토큰은 IDE 비밀번호 저장소(PasswordSafe)에만 보관됩니다.")
                }
            }

            group("GitLab (Merge Request)") {
                row("GitLab 주소:") {
                    cell(gitLabBaseUrlField).columns(COLUMNS_LARGE)
                }.rowComment("예: https://lab.ssafy.com. 비워 두면 기존 값을 유지합니다.")

                row("액세스 토큰:") {
                    cell(gitLabTokenField).columns(COLUMNS_LARGE)
                }.rowComment(
                    "입력하면 IDE 비밀번호 저장소에만 저장하고 다시 표시하지 않습니다. " +
                        "GITLAB_TOKEN 환경 변수가 있으면 그쪽을 먼저 씁니다.",
                )
            }

            row {
                button(RUN_WIZARD_BUTTON) { launchSetupWizard() }
            }.rowComment("Jira 연결·프로젝트·이슈 유형·상태를 마법사로 처음부터 다시 설정합니다.")

            row { cell(problemLabel) }
        }

        reset()
        return component
    }

    override fun isModified(): Boolean = currentEdit().changes(saved) || gitLabTokenField.password.isNotEmpty()

    override fun apply() {
        saved = try {
            settings.save(currentEdit())
        } catch (error: PolicyConfigurationError) {
            throw ConfigurationException(error.message, "Jira Task 설정")
        }
        saveGitLabToken()
        show(saved, problem = null)
        JiraTaskStateListener.publishToAllProjects()
    }

    /**
     * 토큰은 설정 파일이 아니라 IDE 비밀번호 저장소에만 넣고, 입력 칸을 즉시 비운다.
     * 저장할 GitLab 주소를 모르면 넣을 곳이 없으므로 그렇게 알린다.
     */
    private fun saveGitLabToken() {
        val token = String(gitLabTokenField.password).trim()
        if (token.isEmpty()) {
            return
        }

        val baseUrl = saved.gitLabBaseUrl.ifBlank {
            throw ConfigurationException("GitLab 주소를 먼저 입력하세요.", "Jira Task 설정")
        }
        try {
            gitLabTokens.save(baseUrl, token)
        } catch (error: CredentialStorageError) {
            throw ConfigurationException(error.message, "Jira Task 설정")
        }
        gitLabTokenField.text = ""
    }

    override fun reset() {
        when (val load = settings.load()) {
            is SettingsLoad.Loaded -> {
                saved = load.snapshot
                show(saved, problem = null)
            }
            is SettingsLoad.Broken -> {
                saved = SettingsSnapshot.empty()
                show(saved, problem = load.message)
            }
        }
    }

    private fun currentEdit(): SettingsEdit =
        SettingsEdit(projectKeyField.text, baseUrlField.text, gitLabBaseUrlField.text)

    private fun show(snapshot: SettingsSnapshot, problem: String?) {
        projectKeyField.text = snapshot.projectKey
        baseUrlField.text = snapshot.baseUrl
        projectNameLabel.text = snapshot.projectName.orNotConfigured()
        storyIssueTypeLabel.text = snapshot.storyIssueType.orNotConfigured()
        taskIssueTypeLabel.text = snapshot.taskIssueType.orNotConfigured()
        epicIssueTypeLabel.text = snapshot.epicIssueType.orNotConfigured()
        storyStartStatusLabel.text = snapshot.storyStartStatus.orNotConfigured()
        gitLabBaseUrlField.text = snapshot.gitLabBaseUrl
        gitLabTokenField.text = ""
        problemLabel.text = problem.orEmpty()
        problemLabel.isVisible = problem != null
    }

    private fun String.orNotConfigured(): String = ifEmpty { NOT_CONFIGURED }

    companion object {
        const val NOT_CONFIGURED: String = "설정되지 않음"
        const val RUN_WIZARD_BUTTON: String = "설정 마법사 다시 실행"
    }
}

private fun persistedSettings(): EditSettingsUseCase {
    val repository = PolicyConfigRepository()
    return EditSettingsUseCase(repository::loadConfig, repository::saveConfig)
}
