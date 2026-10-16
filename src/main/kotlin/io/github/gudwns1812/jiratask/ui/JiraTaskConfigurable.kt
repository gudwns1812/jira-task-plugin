package io.github.gudwns1812.jiratask.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
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
import io.github.gudwns1812.jiratask.application.AiCliSnapshot
import io.github.gudwns1812.jiratask.application.SettingsSnapshot
import io.github.gudwns1812.jiratask.application.empty
import io.github.gudwns1812.jiratask.domain.mr.GitLabUser
import io.github.gudwns1812.jiratask.ui.mr.MultiPickDialog
import io.github.gudwns1812.jiratask.domain.mr.MergeRequestPort
import io.github.gudwns1812.jiratask.domain.policy.AiProvider
import io.github.gudwns1812.jiratask.domain.policy.PolicyConfigurationError
import io.github.gudwns1812.jiratask.infrastructure.CredentialStorageError
import io.github.gudwns1812.jiratask.infrastructure.GitLabClient
import io.github.gudwns1812.jiratask.infrastructure.GitLabTokenStore
import io.github.gudwns1812.jiratask.infrastructure.PolicyConfigRepository
import io.github.gudwns1812.jiratask.infrastructure.ai.AiCli
import io.github.gudwns1812.jiratask.infrastructure.ai.AiCliLocations
import io.github.gudwns1812.jiratask.infrastructure.ai.AiCliRunner
import io.github.gudwns1812.jiratask.infrastructure.ai.AiClis
import io.github.gudwns1812.jiratask.ui.setup.SetupWizardLauncher
import java.io.File
import javax.swing.JComponent

class JiraTaskConfigurable @JvmOverloads constructor(
    private val settings: EditSettingsUseCase = persistedSettings(),
    private val gitLabTokens: GitLabTokenStore = GitLabTokenStore(),
    private val launchSetupWizard: () -> Unit = { SetupWizardLauncher.launch(null) },
    private val createClient: (String, String) -> MergeRequestPort = { baseUrl, token ->
        GitLabClient(baseUrl, token)
    },
    private val userHome: File = File(System.getProperty("user.home")),
    private val background: (() -> Unit) -> Unit = POOLED,
    private val onEdt: (() -> Unit) -> Unit = ON_EDT,
    private val locate: (AiCli) -> String? = AiCliRunner::locate,
) : Configurable {

    private val projectKeyField = JBTextField()
    private val baseUrlField = JBTextField()
    private val projectNameLabel = JBLabel()
    private val storyIssueTypeLabel = JBLabel()
    private val taskIssueTypeLabel = JBLabel()
    private val epicIssueTypeLabel = JBLabel()
    private val gitLabBaseUrlField = JBTextField()
    private val gitLabTokenField = com.intellij.ui.components.JBPasswordField()

    private val gitLabReviewersField = JBTextField()
    private val problemLabel = JBLabel().apply { isVisible = false }
    private val connectionLabel = JBLabel()
    private val aiEnabledBox = com.intellij.ui.components.JBCheckBox("MR 제목·본문과 커밋 메시지를 AI에게 맡기기")

    private val aiSendDiffBox = com.intellij.ui.components.JBCheckBox("커밋 초안에 변경 내용(diff)까지 보내기")
    private val aiModelBox = ComboBox(arrayOf(MODEL_DEFAULT)).apply { isEditable = true }
    private val aiExecutablePathField = JBTextField()

    private val aiProviderBox = ComboBox(AiClis.all.map { it.displayName }.toTypedArray())

    private val aiLocationLabel = JBLabel()

    private val aiClis: MutableMap<String, AiCliSnapshot> = mutableMapOf()

    private var shownProvider: String = AiProvider.CLAUDE.id

    private var saved: SettingsSnapshot = SettingsSnapshot.empty()

    override fun getDisplayName(): String = "Jira Task"

    override fun createComponent(): JComponent {
        val component = panel {
            row("Jira 프로젝트 키:") {
                cell(projectKeyField).columns(COLUMNS_MEDIUM)
            }

            row("Jira 주소:") {
                cell(baseUrlField).columns(COLUMNS_LARGE)
            }

            group("연결된 Jira 정보") {
                row("프로젝트:") { cell(projectNameLabel) }
                row("스토리 유형:") { cell(storyIssueTypeLabel) }
                row("작업 유형:") { cell(taskIssueTypeLabel) }
                row("에픽 유형:") { cell(epicIssueTypeLabel) }
            }

            group("GitLab (Merge Request)") {
                row("GitLab 주소:") {
                    cell(gitLabBaseUrlField).columns(COLUMNS_LARGE)
                }

                row("액세스 토큰:") {
                    cell(gitLabTokenField).columns(COLUMNS_LARGE)
                }

                row("기본 리뷰어:") {
                    cell(gitLabReviewersField).columns(COLUMNS_MEDIUM)
                    button(PICK_REVIEWERS) { pickReviewers() }
                }

                row {
                    button(TEST_CONNECTION_BUTTON) { testConnection() }
                    cell(connectionLabel)
                }
            }

            group("AI 초안") {
                row {
                    cell(aiEnabledBox)
                }

                row {
                    comment(PRIVACY_NOTICE)
                }

                row("사용할 AI:") {
                    cell(aiProviderBox).columns(COLUMNS_MEDIUM)
                    button(DETECT_AGAIN) { detectAgain() }
                }

                row("") {
                    cell(aiLocationLabel)
                }

                row {
                    cell(aiSendDiffBox)
                }.rowComment(DIFF_NOTICE)

                row("모델:") {
                    cell(aiModelBox).columns(COLUMNS_MEDIUM)
                }

                row("실행 파일 경로:") {
                    cell(aiExecutablePathField).columns(COLUMNS_LARGE)
                }.rowComment(PATH_NOTICE)
            }

            row {
                button(RUN_WIZARD_BUTTON) { launchSetupWizard() }
            }

            row { cell(problemLabel) }
        }

        reset()
        aiProviderBox.addActionListener { showChosenAi() }
        component.applyHangulFallback()
        return component
    }

    override fun isModified(): Boolean = currentEdit().changes(saved) || gitLabTokenField.password.isNotEmpty()

    override fun apply() {
        val previousGitLabBaseUrl = saved.gitLabBaseUrl
        saved = try {
            settings.save(currentEdit())
        } catch (error: PolicyConfigurationError) {
            throw ConfigurationException(error.message, "Jira Task 설정")
        }

        val typed = String(gitLabTokenField.password)
        val tokenFailure = runCatching { saveGitLabToken() }.exceptionOrNull()

        show(saved, problem = null)
        if (tokenFailure != null) {
            gitLabTokenField.text = typed
        }
        noticeGitLabAddressChange(previousGitLabBaseUrl, typed)
        JiraTaskStateListener.publishToAllProjects()

        tokenFailure?.let { throw it }
    }

    private fun noticeGitLabAddressChange(previous: String, typedToken: String) {
        if (previous.isBlank() || previous == saved.gitLabBaseUrl || typedToken.isNotEmpty()) {
            return
        }
        val stored = runCatching { gitLabTokens.get(saved.gitLabBaseUrl) }.getOrNull()
        if (stored.isNullOrBlank()) {
            showConnection(GitLabConnectionReport(ADDRESS_CHANGED, isSuccess = false))
        }
    }

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

    private fun testConnection() {
        val baseUrl = gitLabBaseUrlField.text.trim().ifBlank { saved.gitLabBaseUrl }
        val typed = String(gitLabTokenField.password).trim()

        val token = typed.ifEmpty {
            runCatching { gitLabTokens.get(baseUrl) }.getOrNull().orEmpty()
        }

        GitLabConnectionCheck.inputProblem(baseUrl, token.isNotEmpty())?.let { problem ->
            showConnection(GitLabConnectionReport(problem, isSuccess = false))
            return
        }

        showConnection(GitLabConnectionReport(TESTING, isSuccess = true))
        background {
            val report = try {
                GitLabConnectionCheck.of(createClient(baseUrl, token).verifyIdentity())
            } catch (error: Exception) {
                GitLabConnectionCheck.of(error)
            }
            onEdt { showConnection(report) }
        }
    }

    private fun pickReviewers() {
        val baseUrl = gitLabBaseUrlField.text.trim().ifBlank { saved.gitLabBaseUrl }
        val typed = String(gitLabTokenField.password).trim()
        val token = typed.ifEmpty { runCatching { gitLabTokens.get(baseUrl) }.getOrNull().orEmpty() }

        GitLabConnectionCheck.inputProblem(baseUrl, token.isNotEmpty())?.let { problem ->
            showConnection(GitLabConnectionReport(problem, isSuccess = false))
            return
        }

        val client = createClient(baseUrl, token)
        val chosen = gitLabReviewersField.text.split(',', ' ')
            .map { it.trim().removePrefix("@") }
            .filter { it.isNotEmpty() }
            .map { GitLabUser(id = 0, username = it) }

        val dialog = MultiPickDialog(
            project = null,
            dialogTitle = "기본 리뷰어 고르기",
            items = chosen,
            selected = chosen,
            labelOf = GitLabUser::label,
            search = client::searchUsers,
        )
        if (dialog.showAndGet()) {
            gitLabReviewersField.text = dialog.picked().joinToString(", ") { it.username }
        }
    }

    private fun showConnection(report: GitLabConnectionReport) {
        connectionLabel.text = report.message
    }

    fun chosenAi(): AiCli = AiClis.all.firstOrNull { it.displayName == aiProviderBox.selectedItem } ?: AiClis.default

    private fun rememberTypedCli() {
        aiClis[shownProvider] = AiCliSnapshot(
            model = aiModelValue(),
            executablePath = aiExecutablePathField.text.trim(),
        )
    }

    private fun showChosenAi() {
        val cli = chosenAi()
        if (cli.id == shownProvider) {
            return
        }

        rememberTypedCli()
        shownProvider = cli.id
        showCli(cli, aiClis[cli.id] ?: AiCliSnapshot())
    }

    private fun showCli(cli: AiCli, saved: AiCliSnapshot) {
        fillModels(cli, saved.model)
        aiExecutablePathField.text = saved.executablePath
        detect(cli)
    }

    private fun fillModels(cli: AiCli, chosen: String) {
        aiModelBox.removeAllItems()
        aiModelBox.addItem(MODEL_DEFAULT)
        (listOfNotNull(cli.configuredModel(userHome)) + cli.models).distinct().forEach(aiModelBox::addItem)
        aiModelBox.selectedItem = chosen.ifEmpty { MODEL_DEFAULT }
    }

    private fun detect(cli: AiCli) {
        aiLocationLabel.text = DETECTING
        background {
            val found = runCatching { locate(cli) }.getOrNull()
            onEdt {
                if (chosenAi().id == cli.id) {
                    showLocation(cli, found)
                }
            }
        }
    }

    private fun showLocation(cli: AiCli, found: String?) {
        aiLocationLabel.text = found?.let { "$FOUND_AT $it" } ?: "${cli.displayName}을(를) 찾지 못했습니다. ${cli.installHint}"
    }

    private fun detectAgain() {
        AiCliLocations.forget()
        detect(chosenAi())
    }

    fun locationText(): String = aiLocationLabel.text.orEmpty()

    private fun aiModelValue(): String =
        (aiModelBox.editor.item ?: aiModelBox.selectedItem)?.toString()?.trim()
            ?.takeIf { it != MODEL_DEFAULT }
            .orEmpty()

    fun connectionText(): String = connectionLabel.text.orEmpty()

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

    private fun currentEdit(): SettingsEdit = SettingsEdit(
        projectKey = projectKeyField.text,
        baseUrl = baseUrlField.text,
        gitLabBaseUrl = gitLabBaseUrlField.text,
        gitLabReviewers = gitLabReviewersField.text,
        aiEnabled = aiEnabledBox.isSelected,
        aiProvider = AiProvider.of(chosenAi().id),
        aiModel = aiModelValue(),
        aiExecutablePath = aiExecutablePathField.text,
        aiSendDiff = aiSendDiffBox.isSelected,
    )

    private fun show(snapshot: SettingsSnapshot, problem: String?) {
        projectKeyField.text = snapshot.projectKey
        baseUrlField.text = snapshot.baseUrl
        projectNameLabel.text = snapshot.projectName.orNotConfigured()
        storyIssueTypeLabel.text = snapshot.storyIssueType.orNotConfigured()
        taskIssueTypeLabel.text = snapshot.taskIssueType.orNotConfigured()
        epicIssueTypeLabel.text = snapshot.epicIssueType.orNotConfigured()
        gitLabBaseUrlField.text = snapshot.gitLabBaseUrl
        gitLabTokenField.text = ""
        gitLabReviewersField.text = snapshot.gitLabReviewers
        aiEnabledBox.isSelected = snapshot.aiEnabled
        aiSendDiffBox.isSelected = snapshot.aiSendDiff

        aiClis.clear()
        aiClis.putAll(snapshot.aiClis)
        shownProvider = snapshot.aiProvider
        val cli = AiClis.byId(snapshot.aiProvider)
        aiProviderBox.selectedItem = cli.displayName
        showCli(cli, snapshot.aiCli())
        problemLabel.text = problem.orEmpty()
        problemLabel.isVisible = problem != null
    }

    private fun String.orNotConfigured(): String = ifEmpty { NOT_CONFIGURED }

    companion object {
        val POOLED: (() -> Unit) -> Unit = { ApplicationManager.getApplication().executeOnPooledThread(Runnable(it)) }

        val ON_EDT: (() -> Unit) -> Unit = {
            ApplicationManager.getApplication().invokeLater(Runnable(it), ModalityState.any())
        }

        const val NOT_CONFIGURED: String = "설정되지 않음"
        const val RUN_WIZARD_BUTTON: String = "설정 마법사 다시 실행"
        const val TEST_CONNECTION_BUTTON: String = "연결 테스트"
        const val PICK_REVIEWERS: String = "찾아서 고르기"
        const val TESTING: String = "확인 중…"
        const val MODEL_DEFAULT: String = "CLI에 정해 둔 값 따름"
        const val DETECT_AGAIN: String = "새로고침"
        const val DETECTING: String = "찾는 중…"
        const val FOUND_AT: String = "찾음:"
        const val PATH_NOTICE: String =
            "비워 두면 알아서 찾습니다. 탐지에 실패했을 때만 터미널의 `which`(Windows는 `where`) 결과를 넣으세요."
        const val PRIVACY_NOTICE: String =
            "MR 초안은 브랜치 이름, 커밋 제목, 변경된 파일 경로만 보냅니다. 파일 내용은 보내지 않습니다."
        const val DIFF_NOTICE: String =
            "커밋 초안은 여기에 더해 커밋할 변경 내용(diff)을 보냅니다. " +
                "끄면 파일 경로만 보고 쓰므로 메시지가 덜 정확해집니다."
        const val ADDRESS_CHANGED: String =
            "GitLab 주소가 바뀌었습니다. 토큰은 주소마다 따로 보관하므로 새 주소의 토큰을 다시 입력하세요."
    }
}

private fun persistedSettings(): EditSettingsUseCase {
    val repository = PolicyConfigRepository()
    return EditSettingsUseCase(repository::loadConfig, repository::saveConfig)
}
