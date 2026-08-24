package io.github.gudwns1812.jiratask.domain.validation

data class ValidationMessage(
    val path: String,
    val message: String,
)

data class ValidationResult(
    val errors: List<ValidationMessage>,
    val warnings: List<ValidationMessage>,
    val derived: Derived,
) {
    data class Derived(val labels: List<String>)
}
