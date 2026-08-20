import { execFile } from "node:child_process";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import { resolve } from "node:path";

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
      "--project",
      "S15P11A108",
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
});
