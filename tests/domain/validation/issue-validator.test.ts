import { describe, expect, it } from "vitest";

import type { IssueInput, TaskInput } from "../../../src/domain/input/issue-input.types.js";
import { validateIssue } from "../../../src/domain/validation/issue-validator.js";

const validTask: TaskInput = {
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

describe("validateIssue", () => {
  it("올바른 스토리와 작업의 기본 레이블을 계산한다", () => {
    expect(validateIssue({
      type: "story",
      epicKey: "S15P11A108-10",
      title: "[AUTH-001] 이메일 회원가입",
      branch: "feat/100-signup",
    })).toMatchObject({ errors: [], warnings: [], derived: { labels: ["feat"] } });

    expect(validateIssue(validTask)).toMatchObject({ errors: [], warnings: [], derived: { labels: ["FE"] } });
    expect(validateIssue({ ...validTask, title: "[PLAN] 서비스 목표 정의" })).toMatchObject({
      errors: [],
      warnings: [],
      derived: { labels: ["기획"] },
    });
  });

  it("형식이 잘못된 기능 ID·담당 영역·결과물을 함께 알려준다", () => {
    const result = validateIssue({
      ...validTask,
      title: "[AUTH-01 | Frontend] 회원가입 화면 구현",
      description: { summary: "", deliverables: [] },
    });

    expect(result.errors.map((error) => error.path)).toEqual(
      expect.arrayContaining(["title", "description.summary", "description.deliverables"]),
    );
    expect(result.errors).toHaveLength(4);
  });

  it("알 수 없는 공통 업무 분류와 에픽의 스토리 포인트를 거부한다", () => {
    const invalidCommonTask = validateIssue({ ...validTask, title: "[UNKNOWN] 조사" });
    const epicWithPoints = {
      type: "epic",
      title: "[AUTH] 인증",
      storyPoints: 3,
    } as unknown as IssueInput;

    expect(invalidCommonTask.errors).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ path: "title", message: expect.stringContaining("공식 업무 분류") }),
      ]),
    );
    expect(validateIssue(epicWithPoints).errors).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ path: "storyPoints", message: expect.stringContaining("작업에만") }),
      ]),
    );
  });

  it("TECH 업무에 담당 영역이 없으면 레이블 선택 경고를 남긴다", () => {
    const result = validateIssue({ ...validTask, title: "[TECH] 성능 검증" });

    expect(result.errors).toHaveLength(0);
    expect(result.warnings).toEqual(
      expect.arrayContaining([expect.objectContaining({ path: "title", message: expect.stringContaining("TECH") })]),
    );
  });
});
