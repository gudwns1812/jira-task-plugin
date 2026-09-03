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
    /** CLI 설정 파일이 있는 자리. 테스트가 가짜 홈을 끼운다. */
    private val userHome: File = File(System.getProperty("user.home")),
    private val background: (() -> Unit) -> Unit = POOLED,
    private val onEdt: (() -> Unit) -> Unit = ON_EDT,
    /** 이 CLI가 어디에 깔려 있는지. 프로세스를 띄우므로 테스트는 가짜를 끼운다. */
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

    /**
     * MR 창을 열 때 리뷰어로 미리 넣어 둘 사람들. 쉼표로 나눠 적는다.
     *
     * 이름이 아니라 GitLab 아이디를 받는다 — 이름은 겹치고 바뀌지만 아이디는 그렇지 않다.
     */
    private val gitLabReviewersField = JBTextField()
    private val problemLabel = JBLabel().apply { isVisible = false }
    private val connectionLabel = JBLabel()
    private val aiEnabledBox = com.intellij.ui.components.JBCheckBox("MR 제목·본문과 커밋 메시지를 AI에게 맡기기")

    /**
     * 커밋 초안에 변경 내용(diff)까지 보낼지.
     *
     * 커밋 창의 초안은 이것을 끄면 파일 경로만 보고 쓴다. MR 초안에는 영향이 없다 —
     * 그쪽은 어떤 경우에도 파일 내용을 보내지 않는다.
     */
    private val aiSendDiffBox = com.intellij.ui.components.JBCheckBox("커밋 초안에 변경 내용(diff)까지 보내기")
    /**
     * 모델. 별칭은 늘 그 계열의 최신 모델을 가리키므로 버전을 박아 두는 것보다 낫다.
     *
     * CLI가 문서로 밝힌 것은 `fable`·`opus`·`sonnet`뿐이라 목록에는 그 셋만 둔다. 그 밖의 모델은
     * 전체 이름(`claude-opus-5`)으로 직접 칠 수 있도록 편집을 열어 둔다 — 없는 별칭을 지어내면
     * 사용자는 고르기만 했는데 실행에서 거부당한다.
     */
    private val aiModelBox = ComboBox(arrayOf(MODEL_DEFAULT)).apply { isEditable = true }
    private val aiExecutablePathField = JBTextField()

    /**
     * 어느 AI에게 맡길지.
     *
     * **깔려 있지 않은 것도 목록에서 지우지 않는다.** 숨기면 그런 선택지가 있다는 것도, 설치하면
     * 쓸 수 있다는 것도 알 길이 없다 — AI가 꺼져 있어도 버튼을 남겨 두는 것과 같은 판단이다.
     * 고르면 [aiLocationLabel]이 설치 방법을 알려 준다.
     */
    private val aiProviderBox = ComboBox(AiClis.all.map { it.displayName }.toTypedArray())

    /** 탐지 결과. 찾았으면 어디서 찾았는지, 못 찾았으면 어떻게 까는지 적는다. */
    private val aiLocationLabel = JBLabel()

    /**
     * 화면이 들고 있는 provider별 모델·경로.
     *
     * 저장하지 않고 AI를 바꿔 보는 일이 흔한데, 그때 방금 친 값을 잃지 않으려면 화면이 기억해야 한다.
     */
    private val aiClis: MutableMap<String, AiCliSnapshot> = mutableMapOf()

    /** 지금 칸에 보이고 있는 provider. 콤보를 바꿀 때 **직전 값**을 어디에 넣을지 알아야 한다. */
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

            // GitLab 그룹 **뒤에** 둔다. 앞에 끼우면 편집 칸의 순서가 밀린다.
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
        // 창을 다 만든 뒤에 단다. reset()이 값을 넣는 동안 울리면 아직 없는 값을 옮겨 적는다.
        aiProviderBox.addActionListener { showChosenAi() }
        // 테마 글꼴에 한글이 없으면 이 화면의 글자가 통째로 네모가 된다.
        component.applyHangulFallback()
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

    /**
     * 기본 리뷰어를 이름으로 찾아 고른다.
     *
     * 여기서는 아직 어느 저장소인지 모르므로 GitLab 전체에서 찾는다. 고른 사람은 아이디로 저장한다 —
     * 이름은 겹치고 바뀌지만 아이디는 그렇지 않다.
     */
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

    /** 콤보에서 고른 AI. 목록에 없는 값이 들어올 일은 없지만, 그때는 기본 CLI로 돌아간다. */
    fun chosenAi(): AiCli = AiClis.all.firstOrNull { it.displayName == aiProviderBox.selectedItem } ?: AiClis.default

    /**
     * 칸에 보이는 값을 **지금 보이고 있던** provider 앞으로 적어 둔다.
     *
     * 콤보를 바꾸기 직전에 부른다. 이걸 하지 않으면 Codex를 잠깐 눌러 본 것만으로 Claude 칸에 친
     * 모델 이름이 사라진다.
     */
    private fun rememberTypedCli() {
        aiClis[shownProvider] = AiCliSnapshot(
            model = aiModelValue(),
            executablePath = aiExecutablePathField.text.trim(),
        )
    }

    /** 고른 AI의 값으로 칸을 갈아 끼우고, 어디 깔려 있는지 다시 알아본다. */
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

    /**
     * 모델 후보를 그 CLI의 것으로 갈아 끼운다.
     *
     * 목록에 없는 모델도 칠 수 있게 편집을 열어 둔다 — 없는 별칭을 지어내면 사용자는 고르기만
     * 했는데 실행에서 거부당하지만, 반대로 새로 나온 모델을 못 쓰게 막아서도 안 된다.
     */
    /**
     * 모델 후보를 채운다.
     *
     * **CLI 설정에 적힌 모델이 맨 위**다 — 우리가 아는 유일한 진짜 이름이고, 코드에 박아 둔 예시는
     * 금세 낡는다. 콤보는 편집을 열어 두므로 어떤 이름이든 직접 칠 수 있다.
     */
    private fun fillModels(cli: AiCli, chosen: String) {
        aiModelBox.removeAllItems()
        aiModelBox.addItem(MODEL_DEFAULT)
        (listOfNotNull(cli.configuredModel(userHome)) + cli.models).distinct().forEach(aiModelBox::addItem)
        aiModelBox.selectedItem = chosen.ifEmpty { MODEL_DEFAULT }
    }

    /**
     * 이 CLI가 어디 깔려 있는지 알아본다. 프로세스를 띄우므로 백그라운드에서 부른다.
     *
     * 답이 돌아왔을 때 사용자가 이미 다른 AI를 골랐을 수 있다. 그때는 버린다 — 늦게 온 답이
     * 지금 고른 것의 자리를 덮으면 화면이 거짓말을 한다.
     */
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

    /** 방금 깐 사용자를 위해 기억해 둔 자리를 버리고 다시 찾는다. */
    private fun detectAgain() {
        AiCliLocations.forget()
        detect(chosenAi())
    }

    /** 탐지 결과 문구. 화면을 뒤지지 않고 확인하려고 열어 둔다. */
    fun locationText(): String = aiLocationLabel.text.orEmpty()

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
        // 콤보를 건드리기 **전에** 정한다. 리스너가 울렸을 때 옮겨 적을 자리를 알아야 한다.
        shownProvider = snapshot.aiProvider
        val cli = AiClis.byId(snapshot.aiProvider)
        aiProviderBox.selectedItem = cli.displayName
        showCli(cli, snapshot.aiCli())
        problemLabel.text = problem.orEmpty()
        problemLabel.isVisible = problem != null
    }

    private fun String.orNotConfigured(): String = ifEmpty { NOT_CONFIGURED }

    companion object {
        /** 기본 실행 자리. 테스트가 그 자리에서 결과를 보고 싶을 때 갈아 끼울 수 있게 이름을 붙여 둔다. */
        val POOLED: (() -> Unit) -> Unit = { ApplicationManager.getApplication().executeOnPooledThread(Runnable(it)) }

        /** 설정 화면도 모달이다. 모달리티를 주지 않으면 창이 닫힌 뒤에야 반영된다. */
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
