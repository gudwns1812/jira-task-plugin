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
import io.github.gudwns1812.jiratask.application.SettingsSnapshot
import io.github.gudwns1812.jiratask.application.empty
import io.github.gudwns1812.jiratask.domain.mr.MergeRequestPort
import io.github.gudwns1812.jiratask.domain.policy.PolicyConfigurationError
import io.github.gudwns1812.jiratask.infrastructure.CredentialStorageError
import io.github.gudwns1812.jiratask.infrastructure.GitLabClient
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
    /** 연결 테스트가 쓸 GitLab. 테스트가 가짜를 끼울 수 있게 열어 둔다. */
    private val createClient: (String, String) -> MergeRequestPort = { baseUrl, token ->
        GitLabClient(baseUrl, token)
    },
    private val background: (() -> Unit) -> Unit = {
        ApplicationManager.getApplication().executeOnPooledThread(Runnable(it))
    },
    private val onEdt: (() -> Unit) -> Unit = {
        ApplicationManager.getApplication().invokeLater(Runnable(it), ModalityState.any())
    },
) : Configurable {

    private val projectKeyField = JBTextField()
    private val baseUrlField = JBTextField()
    private val projectNameLabel = JBLabel()
    private val storyIssueTypeLabel = JBLabel()
    private val taskIssueTypeLabel = JBLabel()
    private val epicIssueTypeLabel = JBLabel()
    private val gitLabBaseUrlField = JBTextField()
    private val gitLabTokenField = com.intellij.ui.components.JBPasswordField()
    private val problemLabel = JBLabel().apply { isVisible = false }
    private val connectionLabel = JBLabel()
    private val aiEnabledBox = com.intellij.ui.components.JBCheckBox("MR 제목·본문을 AI에게 맡기기")
    /**
     * 모델. 별칭은 늘 그 계열의 최신 모델을 가리키므로 버전을 박아 두는 것보다 낫다.
     *
     * CLI가 문서로 밝힌 것은 `fable`·`opus`·`sonnet`뿐이라 목록에는 그 셋만 둔다. 그 밖의 모델은
     * 전체 이름(`claude-opus-5`)으로 직접 칠 수 있도록 편집을 열어 둔다 — 없는 별칭을 지어내면
     * 사용자는 고르기만 했는데 실행에서 거부당한다.
     */
    private val aiModelBox = ComboBox(arrayOf(MODEL_DEFAULT, "fable", "opus", "sonnet")).apply {
        isEditable = true
    }
    private val aiExecutablePathField = JBTextField()

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
                        "여기 저장한 토큰이 GITLAB_TOKEN 환경 변수보다 우선합니다.",
                )

                row {
                    button(TEST_CONNECTION_BUTTON) { testConnection() }
                    cell(connectionLabel)
                }.rowComment(
                    "MR을 만들기 전에 토큰이 통하는지 확인합니다. api 스코프가 없으면 조회만 되고 생성에서 막힙니다.",
                )
            }

            // GitLab 그룹 **뒤에** 둔다. 앞에 끼우면 편집 칸의 순서가 밀린다.
            group("AI 초안 (Claude Code)") {
                row {
                    cell(aiEnabledBox)
                }.rowComment(
                    "설치해 로그인해 둔 Claude Code를 그대로 씁니다. API 키를 받지 않으며 구독 요금제로 동작합니다.",
                )

                row {
                    comment(PRIVACY_NOTICE)
                }

                row("모델:") {
                    cell(aiModelBox).columns(COLUMNS_MEDIUM)
                }.rowComment(
                    "고르지 않으면 Claude Code에 설정된 모델을 그대로 씁니다. " +
                        "목록에 없는 모델은 전체 이름으로 직접 적을 수 있습니다(예: claude-opus-5).",
                )

                row("실행 파일 경로:") {
                    cell(aiExecutablePathField).columns(COLUMNS_LARGE)
                }.rowComment("비워 두면 PATH에서 찾습니다. Windows에서는 claude.cmd 를 씁니다.")
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

    /**
     * 설정을 저장하고 토큰을 넣는다.
     *
     * 토큰 저장이 실패해도 **화면 갱신과 알림은 반드시 한다.** 그러지 않으면 주소는 이미 저장됐는데
     * 화면은 옛 값을 보이고 다른 프로젝트는 바뀐 줄도 모르는 어긋난 상태가 남는다.
     * 실패했을 때는 방금 친 토큰을 지우지 않는다 — 다시 시도할 수 있어야 한다.
     */
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

    /**
     * 주소가 바뀌면 토큰을 다시 넣으라고 알린다.
     *
     * 토큰은 주소를 열쇠 삼아 보관하므로(`gitlab|<주소>`) 주소를 바꾸면 예전 토큰을 찾을 수 없다.
     * 그대로 두면 다음 MR에서 "토큰이 없습니다"만 뜨고, 방금 주소를 바꾼 일과 이어 보이지 않는다.
     */
    private fun noticeGitLabAddressChange(previous: String, typedToken: String) {
        if (previous.isBlank() || previous == saved.gitLabBaseUrl || typedToken.isNotEmpty()) {
            return
        }
        val stored = runCatching { gitLabTokens.get(saved.gitLabBaseUrl) }.getOrNull()
        if (stored.isNullOrBlank()) {
            showConnection(GitLabConnectionReport(ADDRESS_CHANGED, isSuccess = false))
        }
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

    /**
     * 입력한 주소와 토큰이 실제로 통하는지 GitLab에 물어본다.
     *
     * 화면에 방금 친 토큰을 우선 쓴다 — 저장하기 전에도 확인할 수 있어야 오타를 그 자리에서 잡는다.
     * 비어 있으면 저장된 토큰으로 확인한다.
     */
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
            // 설정 화면도 모달이다. 모달리티를 주지 않으면 창이 닫힌 뒤에야 반영된다.
            onEdt { showConnection(report) }
        }
    }

    private fun showConnection(report: GitLabConnectionReport) {
        connectionLabel.text = report.message
    }

    /** 고른 모델. 기본값 항목은 "정하지 않음"이라 빈 문자열로 되돌린다. */
    private fun aiModelValue(): String =
        (aiModelBox.editor.item ?: aiModelBox.selectedItem)?.toString()?.trim()
            ?.takeIf { it != MODEL_DEFAULT }
            .orEmpty()

    /** 연결 테스트가 지금 보여 주고 있는 문구. 화면을 뒤지지 않고 확인하려고 열어 둔다. */
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
        aiEnabled = aiEnabledBox.isSelected,
        aiModel = aiModelValue(),
        aiExecutablePath = aiExecutablePathField.text,
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
        aiEnabledBox.isSelected = snapshot.aiEnabled
        aiModelBox.selectedItem = snapshot.aiModel.ifEmpty { MODEL_DEFAULT }
        aiExecutablePathField.text = snapshot.aiExecutablePath
        problemLabel.text = problem.orEmpty()
        problemLabel.isVisible = problem != null
    }

    private fun String.orNotConfigured(): String = ifEmpty { NOT_CONFIGURED }

    companion object {
        const val NOT_CONFIGURED: String = "설정되지 않음"
        const val RUN_WIZARD_BUTTON: String = "설정 마법사 다시 실행"
        const val TEST_CONNECTION_BUTTON: String = "연결 테스트"
        const val TESTING: String = "확인 중…"
        const val MODEL_DEFAULT: String = "Claude Code 설정 따름"
        const val PRIVACY_NOTICE: String =
            "브랜치 이름, 커밋 제목, 변경된 파일 경로가 Anthropic에 전송됩니다. 파일 내용은 보내지 않습니다."
        const val ADDRESS_CHANGED: String =
            "GitLab 주소가 바뀌었습니다. 토큰은 주소마다 따로 보관하므로 새 주소의 토큰을 다시 입력하세요."
    }
}

private fun persistedSettings(): EditSettingsUseCase {
    val repository = PolicyConfigRepository()
    return EditSettingsUseCase(repository::loadConfig, repository::saveConfig)
}
