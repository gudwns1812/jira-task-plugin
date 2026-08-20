import { describe, expect, it } from "vitest";

import { parseEpicTitle, parseStoryTitle, parseTaskTitle } from "../../../src/domain/validation/title-parser.js";

describe("제목 파서", () => {
  it("기능·지원 에픽과 스토리 제목을 해석한다", () => {
    expect(parseEpicTitle("[AUTH] 인증")).toEqual({ kind: "feature", domainCode: "AUTH", summary: "인증" });
    expect(parseEpicTitle("개발 기반 및 인프라 구축")).toEqual({ kind: "support", summary: "개발 기반 및 인프라 구축" });
    expect(parseStoryTitle("[AUTH-001] 이메일 회원가입")).toEqual({
      domainCode: "AUTH",
      sequence: "001",
      summary: "이메일 회원가입",
    });
  });

  it("기능 작업과 공통 업무 작업 제목을 해석한다", () => {
    expect(parseTaskTitle("[AUTH-001 | FE] 이메일 회원가입 화면 구현")).toEqual({
      kind: "feature",
      domainCode: "AUTH",
      sequence: "001",
      area: "FE",
      summary: "이메일 회원가입 화면 구현",
    });
    expect(parseTaskTitle("[PLAN | FE] 와이어프레임 제작")).toEqual({
      kind: "common",
      category: "PLAN",
      area: "FE",
      summary: "와이어프레임 제작",
    });
  });

  it("대괄호 형식이나 공백 규칙이 맞지 않으면 해석하지 않는다", () => {
    expect(parseEpicTitle("[AUTH 인증")).toBeUndefined();
    expect(parseStoryTitle("[AUTH-001]이메일 회원가입")).toBeUndefined();
    expect(parseTaskTitle("[AUTH-001|FE] 회원가입 구현")).toBeUndefined();
  });
});
