import { execFile } from "node:child_process";
import { once } from "node:events";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";

import { describe, expect, it } from "vitest";

const execFileAsync = promisify(execFile);
const projectRoot = resolve(fileURLToPath(new URL(".", import.meta.url)), "../..");
const cliPath = resolve(projectRoot, "src/cli/index.ts");
const tsxLoader = pathToFileURL(resolve(projectRoot, "node_modules/tsx/dist/loader.mjs")).href;

describe("branch create CLI", () => {
  it("프로젝트 키와 숫자 스토리 번호를 조립해 브랜치를 만들고 Jira 상태를 전환한다", async () => {
    const directory = await createRepository();
    const configDirectory = await mkdtemp(join(tmpdir(), "task-jira-cli-branch-config-"));
    let transitioned = false;
    const server = createServer((request, response) => {
      const url = new URL(request.url ?? "/", "http://127.0.0.1");
      response.setHeader("Content-Type", "application/json");

      if (request.method === "GET" && url.pathname === "/rest/api/3/issue/S15P11A108-100") {
        response.end(JSON.stringify({ key: "S15P11A108-100", fields: { issuetype: { name: "Story" }, status: { name: "To Do" } } }));
        return;
      }
      if (request.method === "GET" && url.pathname.endsWith("/transitions")) {
        response.end(JSON.stringify({ transitions: [{ id: "31", to: { name: "In Progress" } }] }));
        return;
      }
      if (request.method === "POST" && url.pathname.endsWith("/transitions")) {
        transitioned = true;
        response.statusCode = 204;
        response.end();
        return;
      }
      response.statusCode = 404;
      response.end(JSON.stringify({}));
    });

    try {
      server.listen(0, "127.0.0.1");
      await once(server, "listening");
      const address = server.address();
      if (!address || typeof address === "string") {
        throw new Error("테스트 서버 주소를 찾을 수 없습니다.");
      }
      const configPath = join(configDirectory, "policy.json");
      await writeFile(
        configPath,
        JSON.stringify({
          projectKey: "S15P11A108",
          jira: { baseUrl: `http://127.0.0.1:${address.port}`, storyIssueType: "Story", inProgressStatus: "In Progress" },
        }),
      );

      const result = await execFileAsync(
        process.execPath,
        ["--import", tsxLoader, cliPath, "branch", "create", "--story", "100", "--name", "signup", "--config", configPath],
        { cwd: directory, env: { ...process.env, JIRA_EMAIL: "user@example.com", JIRA_API_TOKEN: "test-token" } },
      );

      expect(result.stdout).toContain("feat/100-signup");
      expect(result.stdout).toContain("S15P11A108-100");
      expect(transitioned).toBe(true);
      await expect(execFileAsync("git", ["branch", "--show-current"], { cwd: directory })).resolves.toMatchObject({ stdout: "feat/100-signup\n" });
    } finally {
      server.close();
      await rm(directory, { recursive: true, force: true });
      await rm(configDirectory, { recursive: true, force: true });
    }
  });

  it("Jira 설정이나 자격 증명이 없으면 Git 변경 전에 종료 코드 2로 끝난다", async () => {
    const directory = await createRepository();
    const configDirectory = await mkdtemp(join(tmpdir(), "task-jira-cli-branch-config-"));

    try {
      const configPath = join(configDirectory, "policy.json");
      await writeFile(configPath, "{}");
      const result = await runCli(directory, "branch", "create", "--story", "100", "--name", "signup", "--config", configPath);

      expect(result.error).toBeDefined();
      expect(result.error?.code).toBe(2);
      expect(result.stderr).toContain("브랜치 생성 설정이 없습니다");
      await expect(execFileAsync("git", ["branch", "--show-current"], { cwd: directory })).resolves.not.toMatchObject({ stdout: "feat/100-signup\n" });
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
  const directory = await mkdtemp(join(tmpdir(), "task-jira-cli-branch-cli-"));
  await execFileAsync("git", ["init"], { cwd: directory });
  await execFileAsync("git", ["config", "user.email", "test@example.com"], { cwd: directory });
  await execFileAsync("git", ["config", "user.name", "Test"], { cwd: directory });
  await writeFile(join(directory, "README.md"), "test\n");
  await execFileAsync("git", ["add", "README.md"], { cwd: directory });
  await execFileAsync("git", ["commit", "-m", "initial"], { cwd: directory });
  return directory;
}
