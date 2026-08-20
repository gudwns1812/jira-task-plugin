import { mkdir, readFile, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { dirname, join } from "node:path";

import {
  defaultPolicyConfig,
  PolicyConfigurationError,
  resolveWorkPolicy,
} from "../domain/policy/policy-config.js";
import type { WorkPolicy } from "../domain/policy/work-policy.js";

export const DEFAULT_POLICY_CONFIG_PATH = join(homedir(), ".jira-task", "policy.json");

export async function loadWorkPolicy(configPath?: string): Promise<WorkPolicy> {
  const path = configPath ?? DEFAULT_POLICY_CONFIG_PATH;
  let contents: string;

  try {
    contents = await readFile(path, "utf8");
  } catch (error) {
    if (isFileNotFound(error) && !configPath) {
      return defaultPolicyConfig();
    }

    throw new PolicyConfigurationError(`정책 설정 파일을 읽을 수 없습니다: ${path}`);
  }

  let rawConfig: unknown;
  try {
    rawConfig = JSON.parse(contents) as unknown;
  } catch {
    throw new PolicyConfigurationError(`정책 설정 파일이 유효한 JSON이 아닙니다: ${path}`);
  }

  return resolveWorkPolicy(rawConfig);
}

export async function initializePolicyConfig(configPath = DEFAULT_POLICY_CONFIG_PATH): Promise<string> {
  try {
    await mkdir(dirname(configPath), { recursive: true });
    await writeFile(`${configPath}`, `${JSON.stringify(defaultPolicyConfig(), null, 2)}\n`, {
      encoding: "utf8",
      flag: "wx",
    });
  } catch (error) {
    if (isFileAlreadyExists(error)) {
      throw new PolicyConfigurationError(`정책 설정 파일이 이미 존재합니다: ${configPath}`);
    }

    throw new PolicyConfigurationError(`정책 설정 파일을 만들 수 없습니다: ${configPath}`);
  }

  return configPath;
}

function isFileNotFound(error: unknown): boolean {
  return typeof error === "object" && error !== null && "code" in error && error.code === "ENOENT";
}

function isFileAlreadyExists(error: unknown): boolean {
  return typeof error === "object" && error !== null && "code" in error && error.code === "EEXIST";
}
