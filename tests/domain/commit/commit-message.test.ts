import { describe, expect, it } from "vitest";

import { CommitMessageError, createCommitMessage } from "../../../src/domain/commit/commit-message.js";

describe("commit message", () => {
  it("이슈 키와 메시지를 합쳐 커밋 메시지를 만든다", () => {
    expect(createCommitMessage("S15P11A108-105", "이메일 회원가입 화면 구현")).toBe("S15P11A108-105 이메일 회원가입 화면 구현");
  });

  it("메시지 앞뒤 공백을 정리한다", () => {
    expect(createCommitMessage("S15P11A108-105", "  로그인 버그 수정  ")).toBe("S15P11A108-105 로그인 버그 수정");
  });

  it.each(["", "   "])("빈 메시지 %s를 거부한다", (message) => {
    expect(() => createCommitMessage("S15P11A108-105", message)).toThrow(CommitMessageError);
  });
});
