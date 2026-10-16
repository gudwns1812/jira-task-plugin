package io.github.gudwns1812.jiratask.ui.navigation

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.IssueNavigationConfiguration
import com.intellij.openapi.vcs.IssueNavigationLink
import io.github.gudwns1812.jiratask.application.SettingsSnapshot
import io.github.gudwns1812.jiratask.application.from
import io.github.gudwns1812.jiratask.domain.navigation.IssueLinkRule
import io.github.gudwns1812.jiratask.domain.policy.PolicyConfigurationError
import io.github.gudwns1812.jiratask.infrastructure.PolicyConfigRepository

object JiraIssueNavigation {
    fun refresh(
        project: Project,
        repository: PolicyConfigRepository = PolicyConfigRepository(),
        configuration: (Project) -> IssueNavigationConfiguration = IssueNavigationConfiguration::getInstance,
    ) {
        if (project.isDisposed) {
            return
        }

        val snapshot = try {
            SettingsSnapshot.from(repository.loadConfig())
        } catch (error: PolicyConfigurationError) {
            return
        }

        apply(configuration(project), IssueLinkRule.of(snapshot.projectKey, snapshot.baseUrl))
    }

    fun apply(configuration: IssueNavigationConfiguration, rule: IssueLinkRule?) {
        val existing = configuration.links.map { IssueLinkRule(it.issueRegexp, it.linkRegexp) }
        val next = rule?.applyTo(existing) ?: IssueLinkRule.removeOursFrom(existing)

        if (next == existing) {
            return
        }
        configuration.links = next.map { IssueNavigationLink(it.issueRegexp, it.linkRegexp) }
    }
}
