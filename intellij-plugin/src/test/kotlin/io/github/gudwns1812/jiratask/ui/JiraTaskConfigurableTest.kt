package io.github.gudwns1812.jiratask.ui

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurableEP
import com.intellij.openapi.options.ConfigurationException
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.gudwns1812.jiratask.application.EditSettingsUseCase
import io.github.gudwns1812.jiratask.domain.policy.PolicyConfigurationError
import io.github.gudwns1812.jiratask.infrastructure.JiraTaskConfigStorage
import io.github.gudwns1812.jiratask.infrastructure.PolicyConfigRepository
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.text.JTextComponent

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
        assertTrue(texts.contains("진행 중"))
        assertTrue(texts.contains("완료"))
    }

    fun `test never shows the configured email or a password field`() {
        val configurable = configurableOf(configuredRepository())

        val component = configurable.createComponent()

        val texts = textsOf(component)
        assertTrue("이메일이 화면에 노출되면 안 된다", texts.none { it.contains("gudwns1423") })
        assertEmpty(collectPasswordFields(component))
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

        assertTrue(texts.count { it == JiraTaskConfigurable.NOT_CONFIGURED } >= 6)
        assertTrue("기본 정책은 항상 보여준다", texts.any { it.contains("FE, BE, AI, QA, INFRA") })
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

    private fun projectKeyFieldOf(component: JComponent): JTextComponent = editableFieldsOf(component)[0]

    private fun baseUrlFieldOf(component: JComponent): JTextComponent = editableFieldsOf(component)[1]

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

    fun `test lists exactly the two editable fields`() {
        val configurable = configurableOf(configuredRepository())

        val values = textFieldValues(configurable.createComponent())

        assertEquals(listOf("S15P11A108", "https://team.atlassian.net"), values)
    }
}
