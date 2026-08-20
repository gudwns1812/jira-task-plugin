import { describe, expect, it } from "vitest";

import {
  PolicyConfigurationError,
  defaultPolicyConfig,
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
});
