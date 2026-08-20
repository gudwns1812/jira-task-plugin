import type { IssueInput, TaskInput } from "../input/issue-input.types.js";
import {
  DEFAULT_WORK_POLICY,
  isArea,
  isWorkCategory,
  labelForArea,
  labelForCategory,
  type WorkPolicy,
} from "../policy/work-policy.js";
import { parseEpicTitle, parseStoryTitle, parseTaskTitle } from "./title-parser.js";
import type { ValidationMessage, ValidationResult } from "./validation-result.js";

export function validateIssue(input: IssueInput, policy: WorkPolicy = DEFAULT_WORK_POLICY): ValidationResult {
  const errors: ValidationMessage[] = [];
  const warnings: ValidationMessage[] = [];
  const labels: string[] = [];

  if (input.type !== "task" && Object.hasOwn(input, "storyPoints")) {
    errors.push({ path: "storyPoints", message: "스토리 포인트는 작업에만 입력할 수 있습니다." });
  }

  switch (input.type) {
    case "epic":
      validateEpicTitle(input.title, errors);
      break;
    case "story":
      validateStoryTitle(input.title, errors);
      labels.push("feat");
      break;
    case "task":
      validateTask(input, policy, errors, warnings, labels);
      break;
  }

  return { errors, warnings, derived: { labels } };
}

function validateEpicTitle(title: string, errors: ValidationMessage[]): void {
  if (!parseEpicTitle(title)) {
    errors.push({
      path: "title",
      message: "기능 에픽은 '[AUTH] 인증' 형식이어야 하며, 지원 에픽은 대괄호 없이 작성해야 합니다.",
    });
  }
}

function validateStoryTitle(title: string, errors: ValidationMessage[]): void {
  const parsed = parseStoryTitle(title);

  if (!parsed) {
    errors.push({ path: "title", message: "스토리는 '[AUTH-001] 이메일 회원가입' 형식이어야 합니다." });
    return;
  }

  if (parsed.sequence.length !== 3) {
    errors.push({ path: "title", message: "스토리 기능 ID의 번호는 AUTH-001처럼 세 자리여야 합니다." });
  }
}

function validateTask(
  task: TaskInput,
  policy: WorkPolicy,
  errors: ValidationMessage[],
  warnings: ValidationMessage[],
  labels: string[],
): void {
  const parsed = parseTaskTitle(task.title);

  if (!parsed) {
    errors.push({
      path: "title",
      message: "작업은 '[AUTH-001 | FE] 작업명' 또는 '[PLAN] 작업명' 형식이어야 합니다.",
    });
  } else if (parsed.kind === "feature") {
    if (parsed.sequence.length !== 3) {
      errors.push({ path: "title", message: "기능 ID의 번호는 AUTH-001처럼 세 자리여야 합니다." });
    }
    addAreaLabel(parsed.area, policy, errors, labels);
  } else {
    if (!isWorkCategory(policy, parsed.category)) {
      errors.push({ path: "title", message: `공식 업무 분류를 사용해야 합니다: ${policy.workCategories.join(", ")}.` });
    }

    if (parsed.area) {
      addAreaLabel(parsed.area, policy, errors, labels);
    } else if (isWorkCategory(policy, parsed.category)) {
      const label = labelForCategory(policy, parsed.category);
      if (label) {
        labels.push(label);
      } else {
        warnings.push({
          path: "title",
          message: `${parsed.category} 업무는 담당 영역을 지정해 레이블을 선택하는 것을 권장합니다.`,
        });
      }
    }
  }

  if (task.description.summary.trim().length === 0) {
    errors.push({ path: "description.summary", message: "작업 설명을 입력해야 합니다." });
  }

  if (task.description.deliverables.length === 0) {
    errors.push({ path: "description.deliverables", message: "결과물을 하나 이상 입력해야 합니다." });
  } else if (task.description.deliverables.some((deliverable) => deliverable.trim().length < 3)) {
    warnings.push({
      path: "description.deliverables",
      message: "완료 기준을 판단할 수 있도록 결과물을 구체적으로 작성하는 것을 권장합니다.",
    });
  }
}

function addAreaLabel(area: string, policy: WorkPolicy, errors: ValidationMessage[], labels: string[]): void {
  if (!isArea(policy, area)) {
    errors.push({ path: "title", message: `담당 영역은 ${policy.areas.join(", ")} 중 하나여야 합니다.` });
    return;
  }

  labels.push(labelForArea(policy, area));
}
