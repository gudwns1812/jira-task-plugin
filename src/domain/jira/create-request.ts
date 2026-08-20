import type { IssueInput } from "../input/issue-input.types.js";

/** Jira 사이트별 필드 ID를 적용하기 전의 생성 요청 초안이다. */
export interface CreateRequestDraft {
  projectKey: string;
  issueType: IssueInput["type"];
  summary: string;
  description?: string;
  labels: string[];
  assignee?: string;
  priority?: string;
  dueDate?: string;
  policyFields: {
    storyPoints?: number;
    sprint?: string;
  };
  relationships: {
    epicKey?: string;
    storyKey?: string;
  };
}

/** RULE.md에 정의된 이슈 유형별 설명 본문을 만든다. */
export function formatIssueDescription(input: IssueInput): string | undefined {
  switch (input.type) {
    case "epic":
      return undefined;
    case "story":
      return `\`${input.branch}\``;
    case "task":
      return [
        "## 설명",
        input.description.summary,
        "",
        "## 결과물",
        ...input.description.deliverables.map((deliverable) => `[] ${deliverable}`),
      ].join("\n");
  }
}
