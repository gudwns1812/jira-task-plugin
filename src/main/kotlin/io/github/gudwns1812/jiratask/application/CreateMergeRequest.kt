package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.mr.BranchCommit
import io.github.gudwns1812.jiratask.domain.mr.GitLabAuthenticationError
import io.github.gudwns1812.jiratask.domain.mr.GitLabError
import io.github.gudwns1812.jiratask.domain.mr.GitLabNetworkError
import io.github.gudwns1812.jiratask.domain.mr.GitLabProject
import io.github.gudwns1812.jiratask.domain.mr.MergeRequestCommand
import io.github.gudwns1812.jiratask.domain.mr.MergeRequestDraft
import io.github.gudwns1812.jiratask.domain.mr.MergeRequestPort

/** MR을 만들기 전에 브랜치에서 읽어낸 것들. */
data class BranchState(
    val branch: String?,
    val remoteUrl: String?,
    val isPushed: Boolean,
    val commitSubjects: List<String>,
)

/** 화면에 채워 넣을 초안. 사용자가 여기서 제목·본문·대상 브랜치를 고칠 수 있다. */
data class MergeRequestProposal(
    val projectPath: String,
    val sourceBranch: String,
    val targetBranch: String,
    val title: String,
    val description: String,
    val issueKeys: List<String>,
    /** 이미 열린 MR이 있으면 그 주소. 새로 만들지 말라는 뜻이다. */
    val existingUrl: String? = null,
)

sealed interface MergeRequestPrepare {
    data class Ready(val proposal: MergeRequestProposal) : MergeRequestPrepare
    data class Blocked(val message: String) : MergeRequestPrepare
}

sealed interface MergeRequestResult {
    data class Created(val webUrl: String) : MergeRequestResult
    data class Failure(val message: String) : MergeRequestResult
}

/**
 * 브랜치 상태와 커밋에서 MR 초안을 만들고, 확인되면 GitLab에 만든다.
 *
 * 제목과 본문은 **규칙으로** 만든다(TASKS.md 6장). AI는 다음 단계이고, 그때도 이 결과가 기본값으로 남는다.
 */
class CreateMergeRequestUseCase(
    private val gitlab: MergeRequestPort,
    private val issueKeysOf: (List<String>) -> List<String>,
    private val summaryOf: (String) -> String? = { null },
    private val jiraBaseUrl: String? = null,
) {

    fun prepare(state: BranchState, fallbackTarget: String = DEFAULT_TARGET): MergeRequestPrepare {
        val branch = state.branch
            ?: return MergeRequestPrepare.Blocked("현재 브랜치를 알 수 없습니다. 커밋이 없는 상태일 수 있습니다.")

        val remote = GitLabProject.of(state.remoteUrl)
            ?: return MergeRequestPrepare.Blocked(
                state.remoteUrl?.let { "GitLab 프로젝트 주소로 보이지 않습니다: $it" }
                    ?: "git remote(origin)가 없습니다. MR을 만들 프로젝트를 알 수 없습니다.",
            )

        if (!state.isPushed) {
            return MergeRequestPrepare.Blocked("$branch 브랜치를 아직 원격에 올리지 않았습니다. 먼저 푸시하세요.")
        }

        val existing = runCatching { gitlab.openMergeRequest(remote.projectPath, branch) }.getOrNull()
        val target = runCatching { gitlab.defaultBranch(remote.projectPath) }.getOrNull() ?: fallbackTarget
        val keys = issueKeysOf(state.commitSubjects)

        return MergeRequestPrepare.Ready(
            MergeRequestProposal(
                projectPath = remote.projectPath,
                sourceBranch = branch,
                targetBranch = target,
                title = MergeRequestDraft.titleOf(branch, keys, summaryOf),
                description = MergeRequestDraft.descriptionOf(
                    keys,
                    state.commitSubjects.map(::BranchCommit),
                    jiraBaseUrl,
                ),
                issueKeys = keys,
                existingUrl = existing?.webUrl,
            ),
        )
    }

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
                ),
            )
            MergeRequestResult.Created(created.webUrl)
        } catch (error: GitLabAuthenticationError) {
            MergeRequestResult.Failure(error.message.orEmpty())
        } catch (error: GitLabNetworkError) {
            MergeRequestResult.Failure(error.message.orEmpty())
        } catch (error: GitLabError) {
            MergeRequestResult.Failure(error.message.orEmpty())
        } catch (error: Exception) {
            MergeRequestResult.Failure("MR을 만들지 못했습니다.")
        }
    }

    companion object {
        /** GitLab이 기본 브랜치를 알려주지 않을 때의 마지막 수단. */
        const val DEFAULT_TARGET: String = "main"
    }
}
