import { execFile } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
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

  it("config show는 저장된 Jira 이메일을 출력하지 않는다", async () => {
    const directory = await mkdtemp(join(tmpdir(), "task-jira-cli-"));
    const configPath = join(directory, "config.json");

    try {
      await writeFile(configPath, JSON.stringify({ projectKey: "S15P11A108", jira: { baseUrl: "https://team.atlassian.net", email: "secret@example.com" } }));
      const result = await runCli("config", "show", "--config", configPath);

      expect("error" in result).toBe(false);
      expect(result.stdout).not.toContain("secret@example.com");
      expect(result.stdout).toContain("https://team.atlassian.net");
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  });

  it("설정이 없는 비대화형 최초 실행은 마법사를 열지 않고 설정 안내로 종료한다", async () => {
    const home = await mkdtemp(join(tmpdir(), "task-jira-cli-home-"));

    try {
      const result = await runCliWithEnvironment({ HOME: home, USERPROFILE: home });

      expect("error" in result).toBe(true);
      expect((result.error as { code?: number }).code).toBe(2);
      expect(result.stderr).toContain("jira-task config setup");
    } finally {
      await rm(home, { recursive: true, force: true });
    }
  });
});

async function runCliWithEnvironment(environment: NodeJS.ProcessEnv) {
  try {
    return await execFileAsync(process.execPath, ["--import", "tsx", "src/cli/index.ts"], {
      cwd: projectRoot,
      env: { ...process.env, ...environment },
    });
  } catch (error) {
    return {
      stdout: (error as { stdout?: string }).stdout ?? "",
      stderr: (error as { stderr?: string }).stderr ?? "",
      error,
    };
  }
}
