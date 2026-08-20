import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { describe, expect, it } from "vitest";

import { PolicyConfigurationError } from "../../src/domain/policy/policy-config.js";
import {
  initializePolicyConfig,
  loadWorkPolicy,
} from "../../src/infrastructure/policy-config-repository.js";

describe("정책 설정 저장소", () => {
  it("기본 설정 파일을 만들고 다시 읽는다", async () => {
    const directory = await mkdtemp(join(tmpdir(), "task-jira-cli-"));
    const configPath = join(directory, "policy.json");

    try {
      await expect(initializePolicyConfig(configPath)).resolves.toBe(configPath);
      await expect(loadWorkPolicy(configPath)).resolves.toMatchObject({ storyPoints: [1, 2, 3, 5, 8] });
      await expect(initializePolicyConfig(configPath)).rejects.toThrow(PolicyConfigurationError);
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  });

  it("명시한 설정 파일이 없으면 오류를 반환한다", async () => {
    await expect(loadWorkPolicy(join(tmpdir(), "missing-task-jira-policy.json"))).rejects.toThrow(
      PolicyConfigurationError,
    );
  });
});
