package io.github.gudwns1812.jiratask.ui

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurableEP
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.gudwns1812.jiratask.application.EditSettingsUseCase
import io.github.gudwns1812.jiratask.domain.mr.GitLabAuthenticationError
import io.github.gudwns1812.jiratask.domain.mr.GitLabIdentity
import io.github.gudwns1812.jiratask.domain.mr.MergeRequestCommand
import io.github.gudwns1812.jiratask.domain.mr.MergeRequestPort
import io.github.gudwns1812.jiratask.domain.policy.PolicyConfigurationError
import io.github.gudwns1812.jiratask.infrastructure.JiraTaskConfigStorage
import io.github.gudwns1812.jiratask.infrastructure.PolicyConfigRepository
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.text.JTextComponent
import kotlinx.coroutines.runBlocking

class JiraTaskConfigurableTest : BasePlatformTestCase() {

    /** 애플리케이션 서비스 상태를 건드리지 않도록 테스트마다 독립된 저장소를 쓴다. */
    private fun repository(): PolicyConfigRepository = PolicyConfigRepository(JiraTaskConfigStorage())

    private fun configurableOf(repository: PolicyConfigRepository): JiraTaskConfigurable =
        JiraTaskConfigurable(EditSettingsUseCase(repository::loadConfig, repository::saveConfig))

    private fun configuredRepository(): PolicyConfigRepository = repository().apply {
        saveConfig(
            mapOf(
                "projectKey" to "S15P11A108",
                "jira" to mapOf(
                    "baseUrl" to "https://team.atlassian.net",
                    "email" to "gudwns1423@gmail.com",
                    "project" to mapOf("id" to "10000", "name" to "A108 프로젝트"),
                    "issueTypes" to mapOf(
                        "story" to mapOf("id" to "10001", "name" to "스토리"),
                        "task" to mapOf("id" to "10002", "name" to "작업"),
                        "epic" to mapOf("id" to "10003", "name" to "에픽"),
                    ),
                    // task.done은 예전 버전이 저장하던 값이다. 그대로 두어 옛 설정 파일이
                    // 오류 없이 읽히고 화면에도 나타나지 않는 것을 함께 확인한다.
                    "statuses" to mapOf(
                        "story" to mapOf("start" to mapOf("id" to "21", "name" to "진행 중")),
                        "task" to mapOf("done" to mapOf("id" to "31", "name" to "완료")),
                    ),
                ),
            ),
        )
    }

    private fun textsOf(component: Container): List<String> {
        val texts = mutableListOf<String>()
        when (component) {
            is JLabel -> texts += component.text.orEmpty()
            is JTextComponent -> texts += component.text.orEmpty()
        }
        for (child in component.components) {
            if (child is Container) {
                texts += textsOf(child)
            }
        }
        return texts
    }

    private fun textFieldValues(component: JComponent): List<String> {
        val values = mutableListOf<String>()
        fun walk(container: Container) {
            if (container is JTextComponent && container.isEditable) {
                values += container.text.orEmpty()
            }
            container.components.filterIsInstance<Container>().forEach(::walk)
        }
        walk(component)
        return values
    }

    fun `test shows the saved configuration when the panel opens`() {
        val configurable = configurableOf(configuredRepository())

        val texts = textsOf(configurable.createComponent())

        assertTrue("프로젝트 키가 편집 필드에 채워져야 한다", texts.contains("S15P11A108"))
        assertTrue(texts.contains("https://team.atlassian.net"))
        assertTrue(texts.contains("A108 프로젝트"))
        assertTrue(texts.contains("스토리"))
        assertTrue(texts.contains("작업"))
        assertTrue(texts.contains("에픽"))
        // 완료 상태는 더 이상 설정에 없다 — MR 병합을 계기로 automation이 판단한다(RULE.md 4.2).
        assertFalse(texts.contains("완료"))
    }

    fun `test never shows a stored secret, only takes a new one`() {
        val configurable = configurableOf(configuredRepository())

        val component = configurable.createComponent()

        val texts = textsOf(component)
        assertTrue("이메일이 화면에 노출되면 안 된다", texts.none { it.contains("gudwns1423") })

        // GitLab 토큰 칸은 입력 전용이다. 저장된 값을 되읽어 채우지 않는다.
        val secrets = collectPasswordFields(component)
        assertSize(1, secrets)
        assertEmpty((secrets.single() as javax.swing.JPasswordField).password.toList())
    }

    private fun collectPasswordFields(container: Container): List<Container> {
        val found = mutableListOf<Container>()
        if (container is javax.swing.JPasswordField) {
            found += container
        }
        container.components.filterIsInstance<Container>().forEach { found += collectPasswordFields(it) }
        return found
    }

    fun `test shows a placeholder for values that are not configured yet`() {
        val configurable = configurableOf(repository())

        val texts = textsOf(configurable.createComponent())

        assertTrue(texts.count { it == JiraTaskConfigurable.NOT_CONFIGURED } >= 4)
        // 업무 정책은 이슈를 만들 때만 쓰던 값이라 더는 설정에 없다(RULE.md 전제 A).
        assertFalse(texts.any { it.contains("FE, BE, AI, QA, INFRA") })
    }

    fun `test reports no modification until an editable field changes`() {
        val repository = configuredRepository()
        val configurable = configurableOf(repository)
        val component = configurable.createComponent()

        assertFalse(configurable.isModified)

        projectKeyFieldOf(component).text = "OTHER"
        assertTrue(configurable.isModified)

        projectKeyFieldOf(component).text = "S15P11A108"
        assertFalse(configurable.isModified)

        projectKeyFieldOf(component).text = ""
        assertFalse("값을 비운 것은 변경 없음으로 본다", configurable.isModified)
    }

    fun `test apply saves the edited project key and base url`() {
        val repository = configuredRepository()
        val configurable = configurableOf(repository)
        val component = configurable.createComponent()

        projectKeyFieldOf(component).text = "S15P11A999"
        baseUrlFieldOf(component).text = "https://another.atlassian.net"
        configurable.apply()

        val saved = repository.loadConfig()
        assertEquals("S15P11A999", saved.projectKey)
        assertEquals("https://another.atlassian.net", saved.jira?.baseUrl)
        assertEquals("스토리", saved.jira?.issueTypes?.story?.name)
        assertFalse("저장 후에는 변경 사항이 없어야 한다", configurable.isModified)
    }

    @Suppress("DEPRECATION") // ConfigurationException.message는 deprecated지만 사용자에게 보이는 문구를 검증할 수단이다.
    fun `test apply rejects an invalid project key without saving`() {
        val repository = configuredRepository()
        val configurable = configurableOf(repository)
        val component = configurable.createComponent()

        projectKeyFieldOf(component).text = "잘못된 키"

        try {
            configurable.apply()
            fail("ConfigurationException이 발생해야 한다")
        } catch (expected: ConfigurationException) {
            assertTrue(expected.message!!.contains("projectKey"))
        }

        assertEquals("S15P11A108", repository.loadConfig().projectKey)
    }

    fun `test reset restores the saved values after editing`() {
        val repository = configuredRepository()
        val configurable = configurableOf(repository)
        val component = configurable.createComponent()

        projectKeyFieldOf(component).text = "OTHER"
        configurable.reset()

        assertEquals("S15P11A108", projectKeyFieldOf(component).text)
        assertFalse(configurable.isModified)
    }

    fun `test shows a message instead of failing when the stored config is broken`() {
        val storage = JiraTaskConfigStorage().apply { configJson = """{"projectKey": "lowercase"}""" }
        val repository = PolicyConfigRepository(storage)
        val reason = try {
            repository.loadConfig()
            null
        } catch (error: PolicyConfigurationError) {
            error.message
        }
        assertNotNull("손상된 설정은 읽을 때 오류가 나야 한다", reason)

        val texts = textsOf(configurableOf(repository).createComponent())

        assertTrue("읽기 오류를 화면에 안내해야 한다", texts.any { it.contains(reason!!) })
    }

    fun `test is registered as an application configurable under Tools`() {
        val endpoint = ExtensionPointName<ConfigurableEP<Configurable>>("com.intellij.applicationConfigurable")

        val registered = endpoint.extensionList.filter {
            it.instanceClass == JiraTaskConfigurable::class.java.name
        }

        assertSize(1, registered)
        assertEquals("tools", registered.single().parentId)
        assertEquals("Jira Task", registered.single().displayName)
    }

    fun `test offers a button that starts the setup wizard`() {
        var launched = 0
        // 이름을 붙여 넘긴다. 뒤에 함수형 인자가 더 있어 트레일링 람다는 엉뚱한 자리에 붙는다.
        val configurable = JiraTaskConfigurable(
            settings = EditSettingsUseCase(configuredRepository()::loadConfig, configuredRepository()::saveConfig),
            launchSetupWizard = { launched++ },
        )

        val button = buttonsOf(configurable.createComponent())
            .single { it.text == JiraTaskConfigurable.RUN_WIZARD_BUTTON }
        button.doClick()

        assertEquals(1, launched)
    }

    /** 연결 테스트가 부른 GitLab을 기록하는 가짜. 실제 네트워크는 쓰지 않는다. */
    private class RecordingGitLab(private val answer: () -> GitLabIdentity) : MergeRequestPort {
        val calls = mutableListOf<Pair<String, String>>()

        fun asFactory(): (String, String) -> MergeRequestPort = { baseUrl, token ->
            calls += baseUrl to token
            this
        }

        override fun verifyIdentity(): GitLabIdentity = answer()

        override fun openMergeRequest(projectPath: String, sourceBranch: String) = null

        override fun defaultBranch(projectPath: String): String? = null

        override fun create(command: MergeRequestCommand) = error("연결 테스트는 MR을 만들지 않는다")
    }

    private fun configurableWith(
        gitlab: RecordingGitLab,
        repository: PolicyConfigRepository = configuredRepository(),
    ) = JiraTaskConfigurable(
        settings = EditSettingsUseCase(repository::loadConfig, repository::saveConfig),
        createClient = gitlab.asFactory(),
        // 테스트에서는 곧바로 실행해 결과를 바로 본다.
        background = { it() },
        onEdt = { it() },
    )

    private fun clickTestConnection(component: JComponent) {
        buttonsOf(component).single { it.text == JiraTaskConfigurable.TEST_CONNECTION_BUTTON }.doClick()
    }

    fun `test the connection test asks gitlab and shows who the token belongs to`() {
        val gitlab = RecordingGitLab { GitLabIdentity("gudwns1812", scopesKnown = true, canCreateMergeRequest = true) }
        val configurable = configurableWith(gitlab)
        val component = configurable.createComponent()

        gitLabBaseUrlFieldOf(component).text = "https://lab.ssafy.com"
        gitLabTokenFieldOf(component).text = "typed-token"
        clickTestConnection(component)

        assertEquals("https://lab.ssafy.com" to "typed-token", gitlab.calls.single())
        assertTrue(configurable.connectionText().contains("gudwns1812"))
    }

    fun `test the connection test explains an invalid token instead of failing silently`() {
        val gitlab = RecordingGitLab { throw GitLabAuthenticationError() }
        val configurable = configurableWith(gitlab)
        val component = configurable.createComponent()

        gitLabBaseUrlFieldOf(component).text = "https://lab.ssafy.com"
        gitLabTokenFieldOf(component).text = "dead-token"
        clickTestConnection(component)

        val shown = configurable.connectionText()
        assertTrue(shown.startsWith("✗"))
        assertTrue(shown.contains("발급"))
    }

    fun `test the connection test asks for what is missing before calling gitlab`() {
        val gitlab = RecordingGitLab { GitLabIdentity("nobody") }
        val configurable = configurableWith(gitlab, repository())
        val component = configurable.createComponent()

        // 주소도 토큰도 없다. 네트워크를 부르지 않고 무엇이 없는지 말한다.
        clickTestConnection(component)

        assertTrue(gitlab.calls.isEmpty())
        assertTrue(configurable.connectionText().contains(GitLabConnectionCheck.NO_BASE_URL))
    }

    fun `test reports the setup as needed only while nothing is configured`() {
        assertTrue(JiraTaskSetupStartupActivity.setupNeeded(repository()))
        assertFalse(JiraTaskSetupStartupActivity.setupNeeded(configuredRepository()))
    }

    fun `test the startup activity refreshes the current task number`() {
        val refreshed = mutableListOf<Project>()

        runBlocking { JiraTaskSetupStartupActivity { refreshed += it }.execute(project) }

        assertEquals("상태 표시줄 위젯을 숨겨도 캐시가 채워져야 한다", listOf(project), refreshed)
    }

    private fun buttonsOf(container: Container): List<javax.swing.JButton> {
        val found = mutableListOf<javax.swing.JButton>()
        if (container is javax.swing.JButton) {
            found += container
        }
        container.components.filterIsInstance<Container>().forEach { found += buttonsOf(it) }
        return found
    }

    private fun projectKeyFieldOf(component: JComponent): JTextComponent = editableFieldsOf(component)[0]

    private fun baseUrlFieldOf(component: JComponent): JTextComponent = editableFieldsOf(component)[1]

    // 순서는 `test lists exactly the editable fields`가 고정해 둔다.
    private fun gitLabBaseUrlFieldOf(component: JComponent): JTextComponent = editableFieldsOf(component)[2]

    private fun gitLabTokenFieldOf(component: JComponent): JTextComponent = editableFieldsOf(component)[3]

    private fun editableFieldsOf(component: JComponent): List<JTextComponent> {
        val fields = mutableListOf<JTextComponent>()
        fun walk(container: Container) {
            if (container is JTextComponent && container.isEditable) {
                fields += container
            }
            container.components.filterIsInstance<Container>().forEach(::walk)
        }
        walk(component)
        assertTrue("편집 가능한 필드는 프로젝트 키와 Jira 주소 두 개다", fields.size >= 2)
        return fields
    }

    fun `test lists exactly the editable fields`() {
        val configurable = configurableOf(configuredRepository())

        val values = textFieldValues(configurable.createComponent())

        // 프로젝트 키, Jira 주소, GitLab 주소, 그리고 비어 있는 GitLab 토큰 칸.
        assertEquals(listOf("S15P11A108", "https://team.atlassian.net", "", ""), values)
    }
}
