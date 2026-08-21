import { describe, expect, it } from "vitest";

import {
  PolicyConfigurationError,
  defaultPolicyConfig,
  resolveJiraTaskConfig,
  resolveWorkPolicy,
} from "../../../src/domain/policy/policy-config.js";

describe("resolveWorkPolicy", () => {
  it("설정이 없으면 RULE.md 기본 정책을 반환한다", () => {
    expect(defaultPolicyConfig()).toMatchObject({
      areas: ["FE", "BE", "AI", "QA", "INFRA"],
      storyPoints: [1, 2, 3, 5, 8],
      labels: { areas: { INFRA: "Infra" } },
    });
  });

  it("영역과 레이블 설정을 덮어쓴다", () => {
    expect(
      resolveWorkPolicy({
        areas: ["FE", "MOBILE"],
        labels: { areas: { MOBILE: "Mobile" } },
      }),
    ).toMatchObject({
      areas: ["FE", "MOBILE"],
      labels: { areas: { MOBILE: "Mobile" } },
    });
  });

  it("8점을 넘는 포인트와 목록에 없는 레이블 키를 거부한다", () => {
    expect(() => resolveWorkPolicy({ storyPoints: [1, 2, 13] })).toThrow(PolicyConfigurationError);
    expect(() => resolveWorkPolicy({ labels: { areas: { MOBILE: "Mobile" } } })).toThrow(
      PolicyConfigurationError,
    );
  });

  it("Jira 프로젝트 키를 정책과 함께 읽는다", () => {
    expect(resolveJiraTaskConfig({ projectKey: "S15P11A108", areas: ["FE"] })).toMatchObject({
      projectKey: "S15P11A108",
      policy: { areas: ["FE"] },
    });
  });

  it("설정 마법사가 저장한 Jira ID와 표시 이름을 유지한다", () => {
    expect(
      resolveJiraTaskConfig({
        projectKey: "S15P11A108",
        jira: {
          baseUrl: "https://team.atlassian.net",
          project: { id: "10001", name: "특화 프로젝트" },
          issueTypes: { story: { id: "10", name: "스토리" }, task: { id: "11", name: "작업" }, epic: { id: "12", name: "에픽" } },
          statuses: { story: { start: { id: "3", name: "진행 중" } }, task: { done: { id: "4", name: "완료" } } },
        },
      }),
    ).toMatchObject({
      jira: { issueTypes: { story: { id: "10", name: "스토리" } }, statuses: { story: { start: { id: "3" } } } },
    });
  });

  it("비어 있거나 형식이 잘못된 Jira 프로젝트 키를 거부한다", () => {
    expect(() => resolveJiraTaskConfig({ projectKey: "" })).toThrow(PolicyConfigurationError);
    expect(() => resolveJiraTaskConfig({ projectKey: "team project" })).toThrow(PolicyConfigurationError);
  });
});
