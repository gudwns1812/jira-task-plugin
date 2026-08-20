import { execFile } from "node:child_process";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";

import { describe, expect, it } from "vitest";

const execFileAsync = promisify(execFile);
const projectRoot = resolve(fileURLToPath(new URL(".", import.meta.url)), "../..");

async function runCli(...args: string[]) {
  try {
    return await execFileAsync(process.execPath, ["--import", "tsx", "src/cli/index.ts", ...args], {
      cwd: projectRoot,
    });
  } catch (error) {
    return {
      stdout: (error as { stdout?: string }).stdout ?? "",
      stderr: (error as { stderr?: string }).stderr ?? "",
      error,
    };
  }
}

describe("create CLI command", () => {
  it("--dry-run에서 Jira를 호출하지 않고 요청 초안을 JSON으로 출력한다", async () => {
    const result = await runCli(
      "create",
      "--file",
      "tests/fixtures/valid-task.json",
      "--config",
      "tests/fixtures/project-config.json",
      "--dry-run",
    );

    expect("error" in result).toBe(false);
    expect(JSON.parse(result.stdout)).toMatchObject({
      projectKey: "S15P11A108",
      issueType: "task",
      labels: ["FE"],
      relationships: { epicKey: "S15P11A108-10", storyKey: "S15P11A108-100" },
    });
  });

  it("--dry-run이 없으면 실제 Jira 생성을 시도하지 않고 사용법 오류로 끝난다", async () => {
    const result = await runCli(
      "create",
      "--file",
      "tests/fixtures/valid-task.json",
      "--project",
      "S15P11A108",
    );

    expect("error" in result).toBe(true);
    expect(result.stderr).toContain("--dry-run");
  });

  it("프로젝트 키가 없으면 초안을 만들지 않고 설정 방법을 안내한다", async () => {
    const result = await runCli(
      "create",
      "--file",
      "tests/fixtures/valid-task.json",
      "--config",
      "tests/fixtures/no-project-config.json",
      "--dry-run",
    );

    expect("error" in result).toBe(true);
    expect(result.stderr).toContain("config set --project");
  });

  it("--project는 저장된 기본 프로젝트 키를 일시적으로 덮어쓴다", async () => {
    const result = await runCli(
      "create",
      "--file",
      "tests/fixtures/valid-task.json",
      "--config",
      "tests/fixtures/project-config.json",
      "--project",
      "OVERRIDE",
      "--dry-run",
    );

    expect("error" in result).toBe(false);
    expect(JSON.parse(result.stdout)).toMatchObject({ projectKey: "OVERRIDE" });
  });
});

describe("config set CLI command", () => {
  it("기본 프로젝트 키를 설정 파일에 저장한다", async () => {
    const directory = await mkdtemp(join(tmpdir(), "task-jira-cli-"));
    const configPath = join(directory, "config.json");

    try {
      const result = await runCli("config", "set", "--project", "S15P11A108", "--config", configPath);

      expect("error" in result).toBe(false);
      expect(JSON.parse(await readFile(configPath, "utf8"))).toMatchObject({ projectKey: "S15P11A108" });
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  });

  it("잘못된 프로젝트 키는 설정 파일을 만들지 않고 거부한다", async () => {
    const directory = await mkdtemp(join(tmpdir(), "task-jira-cli-"));
    const configPath = join(directory, "config.json");

    try {
      const result = await runCli("config", "set", "--project", "team project", "--config", configPath);

      expect("error" in result).toBe(true);
      await expect(readFile(configPath, "utf8")).rejects.toMatchObject({ code: "ENOENT" });
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  });
});
