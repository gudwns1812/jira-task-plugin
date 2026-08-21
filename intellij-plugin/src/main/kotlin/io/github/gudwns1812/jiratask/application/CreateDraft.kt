package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.input.IssueInput
import io.github.gudwns1812.jiratask.domain.input.IssueInputParseResult
import io.github.gudwns1812.jiratask.domain.input.IssueInputParser
import io.github.gudwns1812.jiratask.domain.jira.CreateRequestDraft
import io.github.gudwns1812.jiratask.domain.policy.WorkPolicy
import io.github.gudwns1812.jiratask.domain.validation.IssueValidator
import io.github.gudwns1812.jiratask.domain.validation.ValidationMessage
import io.github.gudwns1812.jiratask.domain.validation.ValidationResult

data class CreateDraftResult(
    val errors: List<ValidationMessage>,
    val warnings: List<ValidationMessage>,
    val derived: ValidationResult.Derived,
    val draft: CreateRequestDraft? = null,
)

object CreateDraftUseCase {

    /** 입력을 검증한 뒤, Jira API 필드 ID에 독립적인 생성 요청 초안을 만든다. */
    fun execute(rawInput: Any?, projectKey: String, policy: WorkPolicy = WorkPolicy.DEFAULT): CreateDraftResult {
        val parsed = IssueInputParser.parse(rawInput, policy)
        if (parsed is IssueInputParseResult.Failure) {
            return CreateDraftResult(parsed.errors, emptyList(), ValidationResult.Derived(emptyList()))
        }

        val input = (parsed as IssueInputParseResult.Success).input
        val validation = IssueValidator.validate(input, policy)
        if (validation.errors.isNotEmpty()) {
            return CreateDraftResult(validation.errors, validation.warnings, validation.derived)
        }

        return CreateDraftResult(
            errors = validation.errors,
            warnings = validation.warnings,
            derived = validation.derived,
            draft = toCreateRequestDraft(input, projectKey, validation.derived.labels),
        )
    }

    private fun toCreateRequestDraft(input: IssueInput, projectKey: String, labels: List<String>): CreateRequestDraft {
        val description = input.jiraDescription()

        return when (input) {
            is IssueInput.Epic -> CreateRequestDraft(
                projectKey = projectKey,
                issueType = "epic",
                summary = input.title,
                description = description,
                labels = labels,
                policyFields = CreateRequestDraft.PolicyFields(),
                relationships = CreateRequestDraft.Relationships(),
            )
            is IssueInput.Story -> CreateRequestDraft(
                projectKey = projectKey,
                issueType = "story",
                summary = input.title,
                description = description,
                labels = labels,
                policyFields = CreateRequestDraft.PolicyFields(sprint = input.sprint),
                relationships = CreateRequestDraft.Relationships(epicKey = input.epicKey),
            )
            is IssueInput.Task -> CreateRequestDraft(
                projectKey = projectKey,
                issueType = "task",
                summary = input.title,
                description = description,
                labels = labels,
                assignee = input.assignee,
                priority = input.priority,
                dueDate = input.dueDate,
                policyFields = CreateRequestDraft.PolicyFields(storyPoints = input.storyPoints, sprint = input.sprint),
                relationships = CreateRequestDraft.Relationships(epicKey = input.epicKey, storyKey = input.storyKey),
            )
        }
    }
}
