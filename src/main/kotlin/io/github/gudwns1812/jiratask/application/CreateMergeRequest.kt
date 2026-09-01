package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.mr.GitLabAuthenticationError
import io.github.gudwns1812.jiratask.domain.mr.GitLabError
import io.github.gudwns1812.jiratask.domain.mr.GitLabNetworkError
import io.github.gudwns1812.jiratask.domain.mr.GitLabPermissionError
import io.github.gudwns1812.jiratask.domain.mr.GitLabProject
import io.github.gudwns1812.jiratask.domain.mr.MergeRequestCommand
import io.github.gudwns1812.jiratask.domain.mr.MergeRequestDraft
import io.github.gudwns1812.jiratask.domain.mr.MergeRequestPort

/** MR을 만들기 전에 브랜치에서 읽어낸 것들. */
data class BranchState(
    val branch: String?,
    val remoteUrl: String?,
    /** 원격에 올라가 있는가. 확인하지 못했으면 null — 그때는 막지 않는다. */
    val isPushed: Boolean?,
    /**
     * 대상 브랜치에는 없고 이 브랜치에만 있는 커밋 제목들.
     *
     * 값이 아니라 함수다 — 어디서부터 셀지는 대상 브랜치를 정한 뒤에야 알 수 있고,
     * 그 범위를 잘못 잡으면 커밋이 통째로 비어 이슈 키까지 사라진다.
     */
    val commitSubjectsSince: (String) -> List<String>,
    /** MR 대상으로 고를 수 있는 원격 브랜치들. 비어 있으면 사용자가 직접 적는다. */
    val remoteBranches: List<String> = emptyList(),
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
    /**
     * 대상으로 고를 수 있는 브랜치들. 첫 번째가 기본값이다.
     *
     * 기본값을 추측으로 정하지 않는 이유는 그 추측이 자주 틀리기 때문이다 — 통합 브랜치(`develop`,
     * `backend-dev`)를 쓰는 팀에서 저장소 기본 브랜치를 기준으로 삼으면 남의 커밋 수백 개가 딸려 온다.
     */
    val targetCandidates: List<String> = emptyList(),
)

sealed interface MergeRequestPrepare {
    data class Ready(val proposal: MergeRequestProposal) : MergeRequestPrepare
    data class Blocked(val message: String) : MergeRequestPrepare
}

sealed interface MergeRequestResult {
    /** 만들었다. [webUrl]이 null이면 만들어졌지만 주소를 확인하지 못한 것이다. */
    data class Created(val webUrl: String?) : MergeRequestResult
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
    /** 설정된 GitLab 주소. remote가 정말 이 GitLab인지 견주는 데 쓴다. 모르면 견주지 않는다. */
    private val gitLabBaseUrl: String? = null,
    /**
     * 토큰을 어디서 가져왔는지 사람이 읽을 말로. 거부당했을 때 어디를 고쳐야 하는지 알려준다.
     *
     * 계층 규칙 때문에 문자열로 받는다 — 출처를 나타내는 타입은 ui 계층에 있고
     * application은 ui를 알지 않는다(ARCHITECTURE.md 114-119).
     */
    private val tokenOrigin: String? = null,
) {

    fun prepare(state: BranchState, fallbackTarget: String = DEFAULT_TARGET): MergeRequestPrepare {
        val branch = state.branch
            ?: return MergeRequestPrepare.Blocked("현재 브랜치를 알 수 없습니다. 커밋이 없는 상태일 수 있습니다.")

        val remote = GitLabProject.of(state.remoteUrl)
            ?: return MergeRequestPrepare.Blocked(
                state.remoteUrl?.let { "GitLab 프로젝트 주소로 보이지 않습니다: $it" }
                    ?: "git remote(origin)가 없습니다. MR을 만들 프로젝트를 알 수 없습니다.",
            )

        // 토큰을 엉뚱한 서버로 보내지 않는다. remote가 다른 곳이면 요청 자체를 만들지 않는다.
        if (gitLabBaseUrl != null && !remote.matches(gitLabBaseUrl)) {
            return MergeRequestPrepare.Blocked(
                "이 저장소의 remote는 ${remote.host}인데 설정된 GitLab은 $gitLabBaseUrl 입니다. " +
                    "다른 곳에 토큰을 보내지 않으려고 멈췄습니다.",
            )
        }

        // 확실히 안 올라간 경우만 막는다. 확인하지 못한 것은 위반이 아니다(ARCHITECTURE.md 4장).
        if (state.isPushed == false) {
            return MergeRequestPrepare.Blocked("$branch 브랜치를 아직 원격에 올리지 않았습니다. 먼저 푸시하세요.")
        }

        val existing = runCatching { gitlab.openMergeRequest(remote.projectPath, branch) }.getOrNull()
        val target = runCatching { gitlab.defaultBranch(remote.projectPath) }.getOrNull() ?: fallbackTarget

        val skeleton = MergeRequestProposal(
            projectPath = remote.projectPath,
            sourceBranch = branch,
            targetBranch = target,
            title = "",
            description = "",
            issueKeys = emptyList(),
            existingUrl = existing?.webUrl,
            targetCandidates = candidatesOf(state.remoteBranches, target, branch),
        )

        return MergeRequestPrepare.Ready(redraft(state, skeleton, target))
    }

    /**
     * [target]을 기준으로 제목·본문·이슈 키를 다시 만든다.
     *
     * 사용자가 대상 브랜치를 고칠 때마다 불린다 — 기준이 바뀌면 이 브랜치가 추가한 커밋도 바뀌고,
     * 그러면 본문에 적을 이슈 키도 함께 바뀌어야 한다.
     */
    fun redraft(state: BranchState, proposal: MergeRequestProposal, target: String): MergeRequestProposal {
        val subjects = runCatching { state.commitSubjectsSince(target) }.getOrDefault(emptyList())
        val keys = issueKeysOf(subjects)

        return proposal.copy(
            targetBranch = target,
            title = MergeRequestDraft.titleOf(proposal.sourceBranch, keys, summaryOf),
            description = MergeRequestDraft.descriptionOf(keys),
            issueKeys = keys,
        )
    }

    /** 자격 증명 오류에 출처를 덧붙인다. "토큰이 유효하지 않다"만으로는 어느 토큰인지 알 수 없다. */
    private fun withTokenSource(message: String): String =
        tokenOrigin?.takeIf { it.isNotBlank() }?.let { "$message ($it)" } ?: message

    /** 고를 수 있는 대상들. 기본값이 맨 앞에 오고, 자기 자신으로는 MR을 만들 수 없으니 뺀다. */
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
        /** GitLab이 기본 브랜치를 알려주지 않을 때의 마지막 수단. */
        const val DEFAULT_TARGET: String = "main"
    }
}
