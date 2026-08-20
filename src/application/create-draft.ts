import { createIssueInputSchema } from "../domain/input/issue-input.schema.js";
import type { IssueInput } from "../domain/input/issue-input.types.js";
import { formatIssueDescription, type CreateRequestDraft } from "../domain/jira/create-request.js";
import { DEFAULT_WORK_POLICY, type WorkPolicy } from "../domain/policy/work-policy.js";
import { validateIssue } from "../domain/validation/issue-validator.js";
import type { ValidationResult } from "../domain/validation/validation-result.js";

export interface CreateDraftResult extends ValidationResult {
  draft?: CreateRequestDraft;
}

/** 입력을 검증한 뒤, Jira API 필드 ID에 독립적인 생성 요청 초안을 만든다. */
export function createDraft(
  rawInput: unknown,
  projectKey: string,
  policy: WorkPolicy = DEFAULT_WORK_POLICY,
): CreateDraftResult {
  const parsed = createIssueInputSchema(policy).safeParse(rawInput);

  if (!parsed.success) {
    return {
      errors: parsed.error.issues.map((issue) => ({
        path: issue.path.length === 0 ? "input" : issue.path.map(String).join("."),
        message: issue.message,
      })),
      warnings: [],
      derived: { labels: [] },
    };
  }

  const validation = validateIssue(parsed.data, policy);
  if (validation.errors.length > 0) {
    return validation;
  }

  return {
    ...validation,
    draft: toCreateRequestDraft(parsed.data, projectKey, validation.derived.labels),
  };
}

function toCreateRequestDraft(input: IssueInput, projectKey: string, labels: string[]): CreateRequestDraft {
  const description = formatIssueDescription(input);
  const draft: CreateRequestDraft = {
    projectKey,
    issueType: input.type,
    summary: input.title,
    ...(description ? { description } : {}),
    labels,
    policyFields: {},
    relationships: {},
  };

  switch (input.type) {
    case "epic":
      return draft;
    case "story":
      draft.relationships.epicKey = input.epicKey;
      if (input.sprint) {
        draft.policyFields.sprint = input.sprint;
      }
      return draft;
    case "task":
      draft.assignee = input.assignee;
      draft.priority = input.priority;
      draft.dueDate = input.dueDate;
      draft.policyFields.storyPoints = input.storyPoints;
      draft.policyFields.sprint = input.sprint;
      draft.relationships.epicKey = input.epicKey;
      if (input.storyKey) {
        draft.relationships.storyKey = input.storyKey;
      }
      return draft;
  }
}
