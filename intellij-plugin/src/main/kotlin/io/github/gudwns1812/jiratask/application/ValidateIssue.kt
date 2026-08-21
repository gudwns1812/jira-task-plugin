package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.input.IssueInputParseResult
import io.github.gudwns1812.jiratask.domain.input.IssueInputParser
import io.github.gudwns1812.jiratask.domain.policy.WorkPolicy
import io.github.gudwns1812.jiratask.domain.validation.IssueValidator
import io.github.gudwns1812.jiratask.domain.validation.ValidationResult

object ValidateIssueUseCase {
    fun execute(rawInput: Any?, policy: WorkPolicy = WorkPolicy.DEFAULT): ValidationResult =
        when (val parsed = IssueInputParser.parse(rawInput, policy)) {
            is IssueInputParseResult.Failure -> ValidationResult(parsed.errors, emptyList(), ValidationResult.Derived(emptyList()))
            is IssueInputParseResult.Success -> IssueValidator.validate(parsed.input, policy)
        }
}
