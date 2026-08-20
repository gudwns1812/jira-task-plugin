import { createIssueInputSchema } from "../domain/input/issue-input.schema.js";
import { DEFAULT_WORK_POLICY, type WorkPolicy } from "../domain/policy/work-policy.js";
import { validateIssue } from "../domain/validation/issue-validator.js";
import type { ValidationMessage, ValidationResult } from "../domain/validation/validation-result.js";

export function validateRawIssue(rawInput: unknown, policy: WorkPolicy = DEFAULT_WORK_POLICY): ValidationResult {
  const schemaResult = createIssueInputSchema(policy).safeParse(rawInput);

  if (!schemaResult.success) {
    return {
      errors: schemaResult.error.issues.map(toValidationMessage),
      warnings: [],
      derived: { labels: [] },
    };
  }

  return validateIssue(schemaResult.data, policy);
}

function toValidationMessage(issue: { path: PropertyKey[]; message: string }): ValidationMessage {
  return {
    path: issue.path.length === 0 ? "input" : issue.path.map(String).join("."),
    message: issue.message,
  };
}
