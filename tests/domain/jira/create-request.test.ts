import { describe, expect, it } from "vitest";

import {
  formatIssueDescription,
  type CreateRequestDraft,
} from "../../../src/domain/jira/create-request.js";

describe("formatIssueDescription", () => {
  it("작업 설명을 RULE.md의 설명 및 결과물 형식으로 만든다", () => {
    expect(
      formatIssueDescription({
        type: "task",
        epicKey: "S15P11A108-10",
        title: "[AUTH-001 | FE] 이메일 회원가입 화면 구현",
        assignee: "user@example.com",
        priority: "High",
        storyPoints: 3,
        sprint: "Sprint 1",
        dueDate: "2026-08-28",
        description: {
          summary: "회원가입 화면과 입력 상태를 구현한다.",
          deliverables: ["입력 상태와 오류 메시지가 표시된다.", "디자인 시안과 일치한다."],
        },
      }),
    ).toBe(
      "## 설명\n회원가입 화면과 입력 상태를 구현한다.\n\n## 결과물\n[] 입력 상태와 오류 메시지가 표시된다.\n[] 디자인 시안과 일치한다.",
    );
  });

  it("스토리에는 브랜치명을 코드 형식으로 기록하고 에픽은 설명을 만들지 않는다", () => {
    expect(
      formatIssueDescription({
        type: "story",
        epicKey: "S15P11A108-10",
        title: "[AUTH-001] 이메일 회원가입",
        branch: "feat/100-signup",
      }),
    ).toBe("`feat/100-signup`");
    expect(formatIssueDescription({ type: "epic", title: "[AUTH] 인증" })).toBeUndefined();
  });
});

describe("CreateRequestDraft", () => {
  it("Jira 의존 필드와 정책 및 관계 필드를 분리한다", () => {
    const draft: CreateRequestDraft = {
      projectKey: "S15P11A108",
      issueType: "task",
      summary: "[AUTH-001 | FE] 이메일 회원가입 화면 구현",
      description: "## 설명\n...\n\n## 결과물\n[] ...",
      labels: ["FE"],
      assignee: "user@example.com",
      priority: "High",
      dueDate: "2026-08-28",
      policyFields: { storyPoints: 3, sprint: "Sprint 1" },
      relationships: { epicKey: "S15P11A108-10", storyKey: "S15P11A108-100" },
    };

    expect(draft.policyFields).toEqual({ storyPoints: 3, sprint: "Sprint 1" });
    expect(draft.relationships.storyKey).toBe("S15P11A108-100");
  });
});
