package io.github.gudwns1812.jiratask.ui.navigation

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.IssueNavigationConfiguration
import com.intellij.openapi.vcs.IssueNavigationLink
import io.github.gudwns1812.jiratask.application.SettingsSnapshot
import io.github.gudwns1812.jiratask.application.from
import io.github.gudwns1812.jiratask.domain.navigation.IssueLinkRule
import io.github.gudwns1812.jiratask.domain.policy.PolicyConfigurationError
import io.github.gudwns1812.jiratask.infrastructure.PolicyConfigRepository

/**
 * 코드·커밋 로그의 `PROJ-81`을 눌러 Jira로 가게 한다.
 *
 * 링크를 그리는 일은 IntelliJ가 이미 한다 — `Settings | Version Control | Issue Navigation`이
 * 그 자리다. 그래서 플러그인이 하는 일은 **그 항목을 대신 채워 주는 것뿐**이다. 주석·커밋 메시지·
 * VCS 로그 어디서든 동작하고, 우리가 렌더링을 흉내 낼 필요가 없다.
 *
 * 사용자가 손수 넣은 규칙은 건드리지 않는다. 우리가 만든 것만 알아보고 갈아 끼운다.
 */
object JiraIssueNavigation {

    /** 저장된 설정을 읽어 반영한다. 설정이 없으면 우리 링크를 걷어낸다. */
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
            // 설정이 깨졌다고 사용자의 링크까지 손대지 않는다.
            return
        }

        apply(configuration(project), IssueLinkRule.of(snapshot.projectKey, snapshot.baseUrl))
    }

    /** [rule]이 null이면 우리 링크만 걷어낸다. */
    fun apply(configuration: IssueNavigationConfiguration, rule: IssueLinkRule?) {
        val existing = configuration.links.map { IssueLinkRule(it.issueRegexp, it.linkRegexp) }
        val next = rule?.applyTo(existing) ?: IssueLinkRule.removeOursFrom(existing)

        if (next == existing) {
            return
        }
        configuration.links = next.map { IssueNavigationLink(it.issueRegexp, it.linkRegexp) }
    }
}
