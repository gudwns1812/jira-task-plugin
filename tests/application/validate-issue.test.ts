import { describe, expect, it } from "vitest";

import { validateRawIssue } from "../../src/application/validate-issue.js";

const validTask = {
  type: "task",
  epicKey: "S15P11A108-10",
  storyKey: "S15P11A108-100",
  title: "[AUTH-001 | FE] 이메일 회원가입 화면 구현",
  assignee: "user@example.com",
  priority: "High",
  storyPoints: 3,
  sprint: "Sprint 1",
  dueDate: "2026-08-28",
  description: {
    summary: "회원가입 화면과 입력 상태를 구현한다.",
    deliverables: ["입력 상태와 오류 메시지가 표시된다."],
  },
};

describe("validateRawIssue", () => {
  it("입력 스키마와 업무 규칙을 모두 통과한 작업의 레이블을 반환한다", () => {
    expect(validateRawIssue(validTask)).toEqual({
      errors: [],
      warnings: [],
      derived: { labels: ["FE"] },
    });
  });

  it("스키마 오류와 업무 규칙 오류를 각 단계에서 반환한다", () => {
    const schemaError = validateRawIssue({ ...validTask, assignee: "", storyPoints: 13 });
    const ruleError = validateRawIssue({ ...validTask, title: "[AUTH-01 | Frontend] 화면 구현" });

    expect(schemaError.errors.map((error) => error.path)).toEqual(
      expect.arrayContaining(["assignee", "storyPoints"]),
    );
    expect(ruleError.errors).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ path: "title", message: expect.stringContaining("세 자리") }),
        expect.objectContaining({ path: "title", message: expect.stringContaining("담당 영역") }),
      ]),
    );
  });
});
