package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.jira.AdfDocument
import io.github.gudwns1812.jiratask.domain.jira.CreateRequestDraft
import io.github.gudwns1812.jiratask.domain.jira.JiraAuthenticationError
import io.github.gudwns1812.jiratask.domain.jira.JiraFieldIds
import io.github.gudwns1812.jiratask.domain.jira.JiraIssueCreator
import io.github.gudwns1812.jiratask.domain.jira.JiraNetworkError
import io.github.gudwns1812.jiratask.domain.jira.JiraRequestError

/** 이슈 유형 이름이 아니라 이 사이트의 이슈 타입 ID가 필요하다(설정 마법사가 저장해 둔 값). */
data class IssueTypeIds(val epic: String?, val story: String?, val task: String?) {
    fun of(issueType: String): String? = when (issueType) {
        "epic" -> epic
        "story" -> story
        "task" -> task
        else -> null
    }
}

data class CreateIssueCommand(
    val draft: CreateRequestDraft,
    val issueTypeIds: IssueTypeIds,
    /** 스토리와 작업을 연결할 관계 이름. RULE.md의 "연결된 업무 항목"에 해당한다. */
    val linkTypeName: String = "Relates",
    /** 스프린트 필드에 넣을 스프린트 ID. 이름만 알면 넣을 수 없다. */
    val sprintId: Int? = null,
)

sealed interface CreateIssueResult {
    /**
     * 만들어진 이슈. [unsetFields]는 규칙상 필요하지만 이 사이트에서 값을 넣지 못한 항목이고,
     * [linkFailed]는 스토리 연결에 실패했다는 뜻이다 — 둘 다 사람이 Jira에서 마무리해야 한다.
     */
    data class Created(
        val key: String,
        val unsetFields: List<String> = emptyList(),
        val linkFailed: Boolean = false,
    ) : CreateIssueResult

    data class Failure(val message: String) : CreateIssueResult
}

/**
 * 검증된 초안을 실제 Jira 이슈로 만든다. CLI에는 없던 기능이라 이식이 아니라 새로 만든 유스케이스다.
 *
 * 사이트마다 다른 것들을 여기서 흡수한다 — 이슈 타입 ID, 담당자 accountId, 스토리 포인트·스프린트
 * 커스텀 필드 ID. 값을 넣지 못한 항목은 실패로 만들지 않고 [CreateIssueResult.Created.unsetFields]로
 * 알린다(나머지가 다 맞는데 이슈 생성을 막는 편이 더 나쁘다).
 */
class CreateIssueUseCase(private val creator: JiraIssueCreator) {

    fun execute(command: CreateIssueCommand): CreateIssueResult {
        val draft = command.draft
        val issueTypeId = command.issueTypeIds.of(draft.issueType)
            ?: return CreateIssueResult.Failure(
                "이 Jira의 ${draft.issueType} 이슈 타입 ID를 모릅니다. 설정 마법사를 다시 실행하세요.",
            )

        val unset = mutableListOf<String>()
        val fields = mutableMapOf<String, Any?>(
            "project" to mapOf("key" to draft.projectKey),
            "issuetype" to mapOf("id" to issueTypeId),
            "summary" to draft.summary,
        )
        AdfDocument.of(draft.description)?.let { fields["description"] = it }
        if (draft.labels.isNotEmpty()) {
            fields["labels"] = draft.labels
        }
        draft.priority?.let { fields["priority"] = mapOf("name" to it) }
        draft.dueDate?.let { fields["duedate"] = it }
        draft.relationships.epicKey?.let { fields["parent"] = mapOf("key" to it) }

        val fieldIds = try {
            creator.findFieldIds()
        } catch (error: Exception) {
            JiraFieldIds()
        }
        applyAssignee(draft.assignee, fields, unset)
        applyStoryPoints(draft.policyFields.storyPoints, fieldIds, fields, unset)
        applySprint(draft.policyFields.sprint, command.sprintId, fieldIds, fields, unset)

        val created = try {
            creator.createIssue(fields)
        } catch (error: Exception) {
            return CreateIssueResult.Failure(messageOf(error))
        }

        val linkFailed = draft.relationships.storyKey?.let { storyKey ->
            runCatching { creator.linkIssues(storyKey, created.key, command.linkTypeName) }.isFailure
        } ?: false

        return CreateIssueResult.Created(created.key, unset, linkFailed)
    }

    private fun applyAssignee(assignee: String?, fields: MutableMap<String, Any?>, unset: MutableList<String>) {
        if (assignee == null) {
            return
        }
        val accountId = runCatching { creator.findAccountId(assignee) }.getOrNull()
        if (accountId == null) {
            unset += "담당자($assignee)"
        } else {
            fields["assignee"] = mapOf("accountId" to accountId)
        }
    }

    private fun applyStoryPoints(
        storyPoints: Int?,
        fieldIds: JiraFieldIds,
        fields: MutableMap<String, Any?>,
        unset: MutableList<String>,
    ) {
        if (storyPoints == null) {
            return
        }
        val fieldId = fieldIds.storyPoints
        if (fieldId == null) {
            unset += "스토리 포인트($storyPoints)"
        } else {
            fields[fieldId] = storyPoints
        }
    }

    private fun applySprint(
        sprintName: String?,
        sprintId: Int?,
        fieldIds: JiraFieldIds,
        fields: MutableMap<String, Any?>,
        unset: MutableList<String>,
    ) {
        if (sprintName == null) {
            return
        }
        val fieldId = fieldIds.sprint
        // 스프린트 필드는 이름이 아니라 스프린트 ID를 받는다. 스토리에서 ID까지 읽어야 넣을 수 있다.
        if (fieldId == null || sprintId == null) {
            unset += "스프린트($sprintName)"
        } else {
            fields[fieldId] = sprintId
        }
    }

    private fun messageOf(error: Throwable): String = when (error) {
        is JiraAuthenticationError, is JiraNetworkError, is JiraRequestError ->
            error.message ?: "Jira 이슈를 만들지 못했습니다."
        else -> "Jira 이슈를 만들지 못했습니다."
    }
}
