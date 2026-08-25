package io.github.gudwns1812.jiratask.domain.validation

import io.github.gudwns1812.jiratask.domain.input.IssueInput
import io.github.gudwns1812.jiratask.domain.policy.WorkPolicy

object IssueValidator {

    // CLI 버전은 storyPoints가 epic/story에 있으면 오류를 내지만, 여기서는 IssueInput.Epic/Story에
    // storyPoints 필드 자체가 없어 컴파일 타임에 이미 불가능하므로 별도 런타임 검사가 필요 없다.
    fun validate(input: IssueInput, policy: WorkPolicy = WorkPolicy.DEFAULT): ValidationResult {
        val errors = mutableListOf<ValidationMessage>()
        val warnings = mutableListOf<ValidationMessage>()
        val labels = mutableListOf<String>()

        when (input) {
            is IssueInput.Epic -> validateEpicTitle(input.title, errors)
            is IssueInput.Story -> {
                validateStoryTitle(input.title, errors)
                labels.add("feat")
            }
            is IssueInput.Task -> validateTask(input, policy, errors, warnings, labels)
        }

        return ValidationResult(errors, warnings, ValidationResult.Derived(labels))
    }

    private fun validateEpicTitle(title: String, errors: MutableList<ValidationMessage>) {
        if (TitleParser.parseEpicTitle(title) == null) {
            errors.add(
                ValidationMessage(
                    "title",
                    "기능 에픽은 '[AUTH] 인증' 형식이어야 하며, 지원 에픽은 대괄호 없이 작성해야 합니다.",
                ),
            )
        }
    }

    private fun validateStoryTitle(title: String, errors: MutableList<ValidationMessage>) {
        val parsed = TitleParser.parseStoryTitle(title)

        if (parsed == null) {
            errors.add(ValidationMessage("title", "스토리는 '[AUTH-001] 이메일 회원가입' 형식이어야 합니다."))
            return
        }

        if (parsed.sequence.length != 3) {
            errors.add(ValidationMessage("title", "스토리 기능 ID의 번호는 AUTH-001처럼 세 자리여야 합니다."))
        }
    }

    private fun validateTask(
        task: IssueInput.Task,
        policy: WorkPolicy,
        errors: MutableList<ValidationMessage>,
        warnings: MutableList<ValidationMessage>,
        labels: MutableList<String>,
    ) {
        when (val parsed = TitleParser.parseTaskTitle(task.title)) {
            null -> errors.add(
                ValidationMessage("title", "작업은 '[AUTH-001 | FE] 작업명' 또는 '[PLAN] 작업명' 형식이어야 합니다."),
            )
            is ParsedTaskTitle.Feature -> {
                if (parsed.sequence.length != 3) {
                    errors.add(ValidationMessage("title", "기능 ID의 번호는 AUTH-001처럼 세 자리여야 합니다."))
                }
                addAreaLabel(parsed.area, policy, errors, labels)
            }
            is ParsedTaskTitle.Common -> {
                if (!policy.isWorkCategory(parsed.category)) {
                    errors.add(
                        ValidationMessage("title", "공식 업무 분류를 사용해야 합니다: ${policy.workCategories.joinToString(", ")}."),
                    )
                }

                val area = parsed.area
                if (area != null) {
                    addAreaLabel(area, policy, errors, labels)
                } else if (policy.isWorkCategory(parsed.category)) {
                    val label = policy.labelForCategory(parsed.category)
                    if (label != null) {
                        labels.add(label)
                    } else {
                        warnings.add(
                            ValidationMessage("title", "${parsed.category} 업무는 담당 영역을 지정해 레이블을 선택하는 것을 권장합니다."),
                        )
                    }
                }
            }
        }

        if (task.description.summary.trim().isEmpty()) {
            errors.add(ValidationMessage("description.summary", "작업 설명을 입력해야 합니다."))
        }

        if (task.description.deliverables.isEmpty()) {
            errors.add(ValidationMessage("description.deliverables", "결과물을 하나 이상 입력해야 합니다."))
        } else if (task.description.deliverables.any { it.trim().length < 3 }) {
            warnings.add(
                ValidationMessage("description.deliverables", "완료 기준을 판단할 수 있도록 결과물을 구체적으로 작성하는 것을 권장합니다."),
            )
        }
    }

    private fun addAreaLabel(
        area: String,
        policy: WorkPolicy,
        errors: MutableList<ValidationMessage>,
        labels: MutableList<String>,
    ) {
        if (!policy.isArea(area)) {
            errors.add(ValidationMessage("title", "담당 영역은 ${policy.areas.joinToString(", ")} 중 하나여야 합니다."))
            return
        }

        labels.add(policy.labelForArea(area))
    }
}
