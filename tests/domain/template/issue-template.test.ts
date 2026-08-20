import { describe, expect, it } from "vitest";

import { getIssueTemplate } from "../../../src/domain/template/issue-template.js";

describe("getIssueTemplate", () => {
  it("스토리와 작업의 RULE.md 템플릿을 반환한다", () => {
    expect(getIssueTemplate("story")).toContain("feat/100-signup");
    expect(getIssueTemplate("task")).toContain("## 결과물");
  });

  it("지원하지 않는 이슈 유형은 반환하지 않는다", () => {
    expect(getIssueTemplate("epic")).toBeUndefined();
    expect(getIssueTemplate("TASK")).toBeUndefined();
  });
});
