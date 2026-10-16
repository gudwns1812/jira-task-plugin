package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.jira.IssueSummary
import io.github.gudwns1812.jiratask.domain.mr.DraftOutcome
import io.github.gudwns1812.jiratask.domain.mr.DraftRequest
import io.github.gudwns1812.jiratask.domain.mr.DraftWriter
import io.github.gudwns1812.jiratask.domain.mr.GitLabAuthenticationError
import io.github.gudwns1812.jiratask.domain.mr.GitLabError
import io.github.gudwns1812.jiratask.domain.mr.GitLabNetworkError
import io.github.gudwns1812.jiratask.domain.mr.GitLabPermissionError
import io.github.gudwns1812.jiratask.domain.mr.GitLabProject
import io.github.gudwns1812.jiratask.domain.mr.GitLabUser
import io.github.gudwns1812.jiratask.domain.mr.GitLabProjectInfo
import io.github.gudwns1812.jiratask.domain.mr.MergeRequestCommand
import io.github.gudwns1812.jiratask.domain.mr.MergeRequestDraft
import io.github.gudwns1812.jiratask.domain.mr.MergeRequestPort

data class BranchState(
    val branch: String?,
    val remoteUrl: String?,
    val isPushed: Boolean?,
    val commitSubjectsSince: (String) -> List<String>,
    val remoteBranches: List<String> = emptyList(),
    val changedFilesSince: (String) -> List<String> = { emptyList() },
    val templates: Map<String, String> = emptyMap(),
)

data class MergeRequestProposal(
    val projectPath: String,
    val sourceBranch: String,
    val targetBranch: String,
    val title: String,
    val description: String,
    val issueKeys: List<String>,
    val existingUrl: String? = null,
    val targetCandidates: List<String> = emptyList(),
    val templates: Map<String, String> = emptyMap(),
    val templateName: String? = null,
    val assignees: List<GitLabUser> = emptyList(),
    val reviewers: List<GitLabUser> = emptyList(),
    val labels: List<String> = emptyList(),
    val availableLabels: List<String> = emptyList(),
    val availableReviewers: List<GitLabUser> = emptyList(),
)

sealed interface MergeRequestPrepare {
    data class Ready(val proposal: MergeRequestProposal) : MergeRequestPrepare
    data class Blocked(val message: String) : MergeRequestPrepare
}

sealed interface DraftRewrite {
    data class Rewritten(val proposal: MergeRequestProposal) : DraftRewrite
    data class Refused(val message: String) : DraftRewrite
}

sealed interface MergeRequestResult {
    data class Created(val webUrl: String?) : MergeRequestResult
    data class Failure(val message: String) : MergeRequestResult
}

class CreateMergeRequestUseCase(
    private val gitlab: MergeRequestPort,
    private val issueKeysOf: (List<String>) -> List<String>,
    private val summaryOf: (String) -> String? = { null },
    private val gitLabBaseUrl: String? = null,
    private val tokenOrigin: String? = null,
    private val draftWriter: DraftWriter? = null,
    private val defaultReviewers: List<String> = emptyList(),
) {
    fun rewrite(state: BranchState, proposal: MergeRequestProposal): DraftRewrite {
        val writer = draftWriter ?: return DraftRewrite.Refused(DISABLED)

        val request = DraftRequest(
            sourceBranch = proposal.sourceBranch,
            targetBranch = proposal.targetBranch,
            issueKeys = proposal.issueKeys,
            commitSubjects = runCatching { state.commitSubjectsSince(proposal.targetBranch) }
                .getOrDefault(emptyList()),
            changedFiles = runCatching { state.changedFilesSince(proposal.targetBranch) }
                .getOrDefault(emptyList()),
            template = proposal.templates[proposal.templateName],
        )

        return when (val outcome = runCatching { writer.write(request) }.getOrElse { DraftOutcome.Failed(FAILED) }) {
            is DraftOutcome.Written -> DraftRewrite.Rewritten(
                proposal.copy(
                    title = MergeRequestDraft.withDomain(domainFor(proposal.issueKeys), outcome.draft.title),
                    description = outcome.draft.description,
                ),
            )
            is DraftOutcome.Unavailable -> DraftRewrite.Refused(outcome.message)
            is DraftOutcome.Failed -> DraftRewrite.Refused(outcome.message)
        }
    }

    private fun domainFor(issueKeys: List<String>): String? =
        issueKeys.firstNotNullOfOrNull { IssueSummary.domainPrefixOf(summaryOf(it)) }

    fun prepare(state: BranchState, fallbackTarget: String = DEFAULT_TARGET): MergeRequestPrepare {
        val branch = state.branch
            ?: return MergeRequestPrepare.Blocked("현재 브랜치를 알 수 없습니다. 커밋이 없는 상태일 수 있습니다.")

        val remote = GitLabProject.of(state.remoteUrl)
            ?: return MergeRequestPrepare.Blocked(
                state.remoteUrl?.let { "GitLab 프로젝트 주소로 보이지 않습니다: $it" }
                    ?: "git remote(origin)가 없습니다. MR을 만들 프로젝트를 알 수 없습니다.",
            )

        if (gitLabBaseUrl != null && !remote.matches(gitLabBaseUrl)) {
            return MergeRequestPrepare.Blocked(
                "이 저장소의 remote는 ${remote.host}인데 설정된 GitLab은 $gitLabBaseUrl 입니다. " +
                    "다른 곳에 토큰을 보내지 않으려고 멈췄습니다.",
            )
        }

        if (state.isPushed == false) {
            return MergeRequestPrepare.Blocked("$branch 브랜치를 아직 원격에 올리지 않았습니다. 먼저 푸시하세요.")
        }

        val existing = runCatching { gitlab.openMergeRequest(remote.projectPath, branch) }.getOrNull()
        val project = runCatching { gitlab.projectInfo(remote.projectPath) }.getOrNull() ?: GitLabProjectInfo()
        val target = project.defaultBranch ?: fallbackTarget
        val templates = templatesOf(project.mergeRequestTemplate, state.templates)

        val people = runCatching { gitlab.findUsers(remote.projectPath) }.getOrDefault(emptyList())

        val skeleton = MergeRequestProposal(
            projectPath = remote.projectPath,
            sourceBranch = branch,
            targetBranch = target,
            title = "",
            description = "",
            issueKeys = emptyList(),
            existingUrl = existing?.webUrl,
            targetCandidates = candidatesOf(state.remoteBranches, target, branch),
            templates = templates,
            templateName = templates.keys.firstOrNull(),
            assignees = listOfNotNull(runCatching { gitlab.me() }.getOrNull()),
            reviewers = reviewersFrom(remote.projectPath, people),
            availableLabels = runCatching { gitlab.labels(remote.projectPath) }.getOrDefault(emptyList()),
            availableReviewers = people,
        )

        return MergeRequestPrepare.Ready(redraft(state, skeleton, target))
    }

    private fun reviewersFrom(projectPath: String, people: List<GitLabUser>): List<GitLabUser> =
        defaultReviewers.mapNotNull { wanted ->
            people.firstOrNull { it.username.equals(wanted, ignoreCase = true) }
                ?: runCatching { gitlab.findUsers(projectPath, wanted) }.getOrDefault(emptyList())
                    .firstOrNull { it.username.equals(wanted, ignoreCase = true) }
        }

    private fun templatesOf(projectTemplate: String?, files: Map<String, String>): Map<String, String> {
        val ordered = LinkedHashMap<String, String>()

        projectTemplate?.trim()?.takeIf { it.isNotEmpty() }?.let { ordered[PROJECT_TEMPLATE] = it }
        files.entries.filter { it.key.equals("Default", ignoreCase = true) }
            .forEach { ordered.putIfAbsent(it.key, it.value) }
        files.forEach { (name, text) -> ordered.putIfAbsent(name, text) }

        return ordered
    }

    fun redraft(
        state: BranchState,
        proposal: MergeRequestProposal,
        target: String = proposal.targetBranch,
        templateName: String? = proposal.templateName,
    ): MergeRequestProposal {
        val subjects = runCatching { state.commitSubjectsSince(target) }.getOrDefault(emptyList())
        val keys = issueKeysOf(subjects)

        return proposal.copy(
            targetBranch = target,
            templateName = templateName,
            title = MergeRequestDraft.titleOf(proposal.sourceBranch, keys, summaryOf),
            description = MergeRequestDraft.descriptionOf(keys, proposal.templates[templateName]),
            issueKeys = keys,
        )
    }

    private fun withTokenSource(message: String): String =
        tokenOrigin?.takeIf { it.isNotBlank() }?.let { "$message ($it)" } ?: message

    private fun candidatesOf(remoteBranches: List<String>, target: String, sourceBranch: String): List<String> =
        (listOf(target) + remoteBranches).distinct().filter { it != sourceBranch }

    fun create(proposal: MergeRequestProposal, title: String, description: String): MergeRequestResult {
        if (title.isBlank()) {
            return MergeRequestResult.Failure("MR 제목은 비울 수 없습니다.")
        }
        if (proposal.sourceBranch == proposal.targetBranch) {
            return MergeRequestResult.Failure("원본 브랜치와 대상 브랜치가 같습니다.")
        }

        return try {
            val created = gitlab.create(
                MergeRequestCommand(
                    projectPath = proposal.projectPath,
                    sourceBranch = proposal.sourceBranch,
                    targetBranch = proposal.targetBranch,
                    title = title.trim(),
                    description = description,
                    assigneeIds = proposal.assignees.map { it.id },
                    reviewerIds = proposal.reviewers.map { it.id },
                    labels = proposal.labels,
                ),
            )
            MergeRequestResult.Created(created.webUrl)
        } catch (error: GitLabAuthenticationError) {
            MergeRequestResult.Failure(withTokenSource(error.message.orEmpty()))
        } catch (error: GitLabPermissionError) {
            MergeRequestResult.Failure(withTokenSource(error.message.orEmpty()))
        } catch (error: GitLabNetworkError) {
            MergeRequestResult.Failure(error.message.orEmpty())
        } catch (error: GitLabError) {
            MergeRequestResult.Failure(error.message.orEmpty())
        } catch (error: Exception) {
            MergeRequestResult.Failure("MR을 만들지 못했습니다.")
        }
    }

    companion object {
        const val DEFAULT_TARGET: String = "main"

        const val PROJECT_TEMPLATE: String = "프로젝트 기본"

        const val DISABLED: String = "AI 초안이 꺼져 있습니다. 설정에서 켜세요."
        const val FAILED: String = "AI 초안을 만들지 못했습니다."
    }
}
