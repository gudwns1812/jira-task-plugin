import { describe, expect, it } from "vitest";

import { createDraft } from "../../src/application/create-draft.js";
import { resolveWorkPolicy } from "../../src/domain/policy/policy-config.js";

const task = {
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

describe("createDraft", () => {
  it("정상 작업을 Jira에 독립적인 생성 초안으로 변환한다", () => {
    expect(createDraft(task, "S15P11A108")).toEqual({
      errors: [],
      warnings: [],
      derived: { labels: ["FE"] },
      draft: {
        projectKey: "S15P11A108",
        issueType: "task",
        summary: task.title,
        description: "## 설명\n회원가입 화면과 입력 상태를 구현한다.\n\n## 결과물\n[] 입력 상태와 오류 메시지가 표시된다.",
        labels: ["FE"],
        assignee: "user@example.com",
        priority: "High",
        dueDate: "2026-08-28",
        policyFields: { storyPoints: 3, sprint: "Sprint 1" },
        relationships: { epicKey: "S15P11A108-10", storyKey: "S15P11A108-100" },
      },
    });
  });

  it("정상 스토리와 에픽도 각각 필요한 값만 가진 초안으로 변환한다", () => {
    expect(
      createDraft(
        {
          type: "story",
          epicKey: "S15P11A108-10",
          title: "[AUTH-001] 이메일 회원가입",
          branch: "feat/100-signup",
          sprint: "Sprint 1",
        },
        "S15P11A108",
      ).draft,
    ).toEqual({
      projectKey: "S15P11A108",
      issueType: "story",
      summary: "[AUTH-001] 이메일 회원가입",
      description: "`feat/100-signup`",
      labels: ["feat"],
      policyFields: { sprint: "Sprint 1" },
      relationships: { epicKey: "S15P11A108-10" },
    });

    expect(createDraft({ type: "epic", title: "[AUTH] 인증" }, "S15P11A108").draft).toEqual({
      projectKey: "S15P11A108",
      issueType: "epic",
      summary: "[AUTH] 인증",
      labels: [],
      policyFields: {},
      relationships: {},
    });
  });

  it("스키마 또는 업무 규칙 오류가 있으면 초안을 만들지 않는다", () => {
    const result = createDraft({ ...task, storyPoints: 13 }, "S15P11A108");

    expect(result.draft).toBeUndefined();
    expect(result.errors).toEqual(expect.arrayContaining([expect.objectContaining({ path: "storyPoints" })]));
  });

  it("사용자 정책으로 계산한 레이블을 초안에 사용한다", () => {
    const policy = resolveWorkPolicy({
      areas: ["FE", "MOBILE"],
      labels: { areas: { MOBILE: "Mobile" } },
    });

    expect(
      createDraft({ ...task, title: "[AUTH-001 | MOBILE] 모바일 회원가입 화면 구현" }, "PROJECT", policy).draft,
    ).toMatchObject({ projectKey: "PROJECT", labels: ["Mobile"] });
  });
});
