import { describe, expect, it } from "vitest";

import { issueInputSchema } from "../../../src/domain/input/issue-input.schema.js";
import { STORY_POINTS } from "../../../src/domain/policy/work-policy.js";

const validTask = {
  type: "task",
  epicKey: "S15P11A108-10",
  storyKey: "S15P11A108-100",
  title: "[AUTH-001 | FE] 이메일 회원가입 화면 및 입력 상태 구현",
  assignee: "user@example.com",
  priority: "High",
  storyPoints: 3,
  sprint: "Sprint 1",
  dueDate: "2026-08-28",
  description: {
    summary: "회원가입 화면과 이메일·비밀번호 입력 상태를 구현한다.",
    deliverables: ["이메일·비밀번호 입력 및 오류 상태가 표시된다."],
  },
};

describe("issueInputSchema", () => {
  it("에픽, 스토리, 작업의 올바른 입력을 파싱한다", () => {
    const epic = issueInputSchema.parse({ type: "epic", title: "[AUTH] 인증" });
    const story = issueInputSchema.parse({
      type: "story",
      epicKey: "S15P11A108-10",
      title: "[AUTH-001] 이메일 회원가입",
      branch: "feat/100-signup",
    });
    const task = issueInputSchema.parse(validTask);

    expect(epic.type).toBe("epic");
    expect(story.type).toBe("story");
    expect(task).toMatchObject(validTask);
    expect(STORY_POINTS).toEqual([1, 2, 3, 5, 8]);
  });

  it("작업의 필수 항목이 빠지면 거부한다", () => {
    const { assignee: _assignee, ...taskWithoutAssignee } = validTask;

    const result = issueInputSchema.safeParse(taskWithoutAssignee);

    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues.some((issue) => issue.path.join(".") === "assignee")).toBe(true);
    }
  });

  it("허용되지 않은 포인트와 달력에 없는 기한을 거부한다", () => {
    const result = issueInputSchema.safeParse({
      ...validTask,
      storyPoints: 4,
      dueDate: "2026-02-29",
    });
    const oversizedResult = issueInputSchema.safeParse({ ...validTask, storyPoints: 13 });

    expect(result.success).toBe(false);
    expect(oversizedResult.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues.map((issue) => issue.path.join("."))).toEqual(
        expect.arrayContaining(["storyPoints", "dueDate"]),
      );
    }
  });

  it("서브 작업 유형과 알 수 없는 필드를 거부한다", () => {
    const subtaskResult = issueInputSchema.safeParse({ type: "subtask", title: "사용 금지" });
    const unknownFieldResult = issueInputSchema.safeParse({
      type: "epic",
      title: "[AUTH] 인증",
      owner: "someone@example.com",
    });

    expect(subtaskResult.success).toBe(false);
    expect(unknownFieldResult.success).toBe(false);
  });
});
