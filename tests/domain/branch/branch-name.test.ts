import { describe, expect, it } from "vitest";

import { BranchNameError, createBranchName, createStoryKey } from "../../../src/domain/branch/branch-name.js";

describe("branch name", () => {
  it("프로젝트 키와 스토리 번호로 Jira 키와 브랜치명을 만든다", () => {
    expect(createStoryKey("S15P11A108", "100")).toBe("S15P11A108-100");
    expect(createBranchName("100", "email-signup")).toBe("feat/100-email-signup");
  });

  it.each(["0", "-1", "100a", " 100"]) ("잘못된 스토리 번호 %s를 거부한다", (storyNumber) => {
    expect(() => createStoryKey("S15P11A108", storyNumber)).toThrow(BranchNameError);
  });

  it.each(["Email", "email signup", "-email", "email-", "email--signup"]) ("잘못된 브랜치 이름 %s를 거부한다", (name) => {
    expect(() => createBranchName("100", name)).toThrow(BranchNameError);
  });
});
