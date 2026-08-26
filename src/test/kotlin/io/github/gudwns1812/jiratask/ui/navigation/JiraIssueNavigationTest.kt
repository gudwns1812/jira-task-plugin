package io.github.gudwns1812.jiratask.ui.navigation

import com.intellij.openapi.vcs.IssueNavigationConfiguration
import com.intellij.openapi.vcs.IssueNavigationLink
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.gudwns1812.jiratask.domain.navigation.IssueLinkRule
import io.github.gudwns1812.jiratask.infrastructure.JiraTaskConfigStorage
import io.github.gudwns1812.jiratask.infrastructure.PolicyConfigRepository

/** IDE의 Issue Navigation 설정을 대신 채워 준다. 사용자 규칙을 지우지 않는 것이 핵심이다. */
class JiraIssueNavigationTest : BasePlatformTestCase() {

    private fun configuration(): IssueNavigationConfiguration =
        IssueNavigationConfiguration.getInstance(project).also { it.links = emptyList() }

    private fun repository(configured: Boolean = true, baseUrl: String? = "https://team.atlassian.net") =
        PolicyConfigRepository(JiraTaskConfigStorage()).apply {
            if (configured) {
                saveConfig(
                    buildMap {
                        put("projectKey", "S15P11A108")
                        baseUrl?.let { put("jira", mapOf("baseUrl" to it)) }
                    },
                )
            }
        }

    private fun linksOf(configuration: IssueNavigationConfiguration) =
        configuration.links.map { it.issueRegexp to it.linkRegexp }

    private fun theirs() = IssueNavigationLink("""[A-Z]+-\d+""", "https://old.example.com/issue/\$0")

    fun `test fills the IDE setting from the saved configuration`() {
        val configuration = configuration()

        JiraIssueNavigation.refresh(project, repository()) { configuration }

        assertEquals(
            listOf("""\bS15P11A108-\d+\b""" to "https://team.atlassian.net/browse/\$0"),
            linksOf(configuration),
        )
    }

    fun `test keeps a rule the user wrote by hand`() {
        val configuration = configuration().apply { links = listOf(theirs()) }

        JiraIssueNavigation.refresh(project, repository()) { configuration }

        assertSize(2, configuration.links)
        assertEquals("""[A-Z]+-\d+""", configuration.links.first().issueRegexp)
    }

    fun `test replaces its own rule instead of piling up`() {
        val configuration = configuration()

        JiraIssueNavigation.refresh(project, repository()) { configuration }
        JiraIssueNavigation.refresh(project, repository()) { configuration }

        assertSize(1, configuration.links)
    }

    fun `test rewrites its rule when the project key changed`() {
        val configuration = configuration()
        JiraIssueNavigation.apply(configuration, IssueLinkRule.of("OLD", "https://team.atlassian.net"))

        JiraIssueNavigation.refresh(project, repository()) { configuration }

        assertEquals(listOf("""\bS15P11A108-\d+\b"""), configuration.links.map { it.issueRegexp })
    }

    fun `test removes only its own rule when nothing is configured`() {
        val configuration = configuration().apply { links = listOf(theirs()) }
        JiraIssueNavigation.refresh(project, repository()) { configuration }

        JiraIssueNavigation.refresh(project, repository(configured = false)) { configuration }

        assertEquals(listOf("""[A-Z]+-\d+"""), configuration.links.map { it.issueRegexp })
    }

    fun `test writes nothing when the jira address is missing`() {
        val configuration = configuration()

        JiraIssueNavigation.refresh(project, repository(baseUrl = null)) { configuration }

        assertEmpty(configuration.links)
    }

    fun `test leaves the user's rules alone when the stored config is broken`() {
        val storage = JiraTaskConfigStorage().apply { configJson = """{"projectKey": "lowercase"}""" }
        val configuration = configuration().apply { links = listOf(theirs()) }

        JiraIssueNavigation.refresh(project, PolicyConfigRepository(storage)) { configuration }

        assertSize(1, configuration.links)
    }

    fun `test does not touch the setting when nothing would change`() {
        val configuration = configuration()
        JiraIssueNavigation.refresh(project, repository()) { configuration }
        val before = configuration.links

        JiraIssueNavigation.refresh(project, repository()) { configuration }

        assertSame("같은 값이면 다시 쓰지 않는다", before, configuration.links)
    }
}
