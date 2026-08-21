import { execFile } from "node:child_process";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";

import { describe, expect, it } from "vitest";

const execFileAsync = promisify(execFile);
const projectRoot = resolve(fileURLToPath(new URL(".", import.meta.url)), "../..");
const cliPath = resolve(projectRoot, "src/cli/index.ts");
const tsxLoader = pathToFileURL(resolve(projectRoot, "node_modules/tsx/dist/loader.mjs")).href;

describe("task start / commit CLI", () => {
  it("작업 번호를 저장한 뒤 이슈 키를 붙여 커밋한다", async () => {
    const directory = await createRepository();
    const configDirectory = await mkdtemp(join(tmpdir(), "task-jira-cli-commit-config-"));

    try {
      const configPath = join(configDirectory, "policy.json");
      await writeFile(configPath, JSON.stringify({ projectKey: "S15P11A108" }));

      const start = await runCli(directory, "task", "start", "--number", "105");
      expect(start.stdout).toContain("105");

      await writeFile(join(directory, "feature.txt"), "feature");
      await execFileAsync("git", ["add", "feature.txt"], { cwd: directory });

      const commit = await runCli(directory, "commit", "-m", "이메일 회원가입 화면 구현", "--config", configPath);
      expect(commit.stdout).toContain("S15P11A108-105 이메일 회원가입 화면 구현");

      await expect(execFileAsync("git", ["log", "-1", "--pretty=%s"], { cwd: directory })).resolves.toMatchObject({
        stdout: "S15P11A108-105 이메일 회원가입 화면 구현\n",
      });
    } finally {
      await rm(directory, { recursive: true, force: true });
      await rm(configDirectory, { recursive: true, force: true });
    }
  });

  it("작업 번호를 저장하지 않았으면 커밋을 거부한다", async () => {
    const directory = await createRepository();
    const configDirectory = await mkdtemp(join(tmpdir(), "task-jira-cli-commit-config-"));

    try {
      const configPath = join(configDirectory, "policy.json");
      await writeFile(configPath, JSON.stringify({ projectKey: "S15P11A108" }));
      await writeFile(join(directory, "feature.txt"), "feature");
      await execFileAsync("git", ["add", "feature.txt"], { cwd: directory });

      const result = await runCli(directory, "commit", "-m", "커밋", "--config", configPath);

      expect(result.error).toBeDefined();
      expect(result.stderr).toContain("현재 작업 번호가 없습니다");
    } finally {
      await rm(directory, { recursive: true, force: true });
      await rm(configDirectory, { recursive: true, force: true });
    }
  });

  it("스테이지된 변경 사항이 없으면 커밋을 거부한다", async () => {
    const directory = await createRepository();
    const configDirectory = await mkdtemp(join(tmpdir(), "task-jira-cli-commit-config-"));

    try {
      const configPath = join(configDirectory, "policy.json");
      await writeFile(configPath, JSON.stringify({ projectKey: "S15P11A108" }));
      await runCli(directory, "task", "start", "--number", "105");

      const result = await runCli(directory, "commit", "-m", "커밋", "--config", configPath);

      expect(result.error).toBeDefined();
      expect(result.stderr).toContain("스테이지된 변경 사항이 없습니다");
    } finally {
      await rm(directory, { recursive: true, force: true });
      await rm(configDirectory, { recursive: true, force: true });
    }
  });
});

async function runCli(cwd: string, ...args: string[]) {
  try {
    return await execFileAsync(process.execPath, ["--import", tsxLoader, cliPath, ...args], { cwd, env: { ...process.env } });
  } catch (error) {
    const failure = error as { stdout?: string; stderr?: string; code?: number };
    return { stdout: failure.stdout ?? "", stderr: failure.stderr ?? "", error: failure };
  }
}

async function createRepository(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), "task-jira-cli-commit-cli-"));
  await execFileAsync("git", ["init"], { cwd: directory });
  await execFileAsync("git", ["config", "user.email", "test@example.com"], { cwd: directory });
  await execFileAsync("git", ["config", "user.name", "Test"], { cwd: directory });
  await writeFile(join(directory, "README.md"), "test\n");
  await execFileAsync("git", ["add", "README.md"], { cwd: directory });
  await execFileAsync("git", ["commit", "-m", "initial"], { cwd: directory });
  return directory;
}
