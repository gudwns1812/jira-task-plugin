import { issueInputSchema } from "../domain/input/issue-input.schema.js";
import { validateIssue } from "../domain/validation/issue-validator.js";
import type { ValidationMessage, ValidationResult } from "../domain/validation/validation-result.js";

export function validateRawIssue(rawInput: unknown): ValidationResult {
  const schemaResult = issueInputSchema.safeParse(rawInput);

  if (!schemaResult.success) {
    return {
      errors: schemaResult.error.issues.map(toValidationMessage),
      warnings: [],
      derived: { labels: [] },
    };
  }

  return validateIssue(schemaResult.data);
}

function toValidationMessage(issue: { path: PropertyKey[]; message: string }): ValidationMessage {
  return {
    path: issue.path.length === 0 ? "input" : issue.path.map(String).join("."),
    message: issue.message,
  };
}
