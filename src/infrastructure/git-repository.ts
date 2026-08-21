import { execFile } from "node:child_process";
import { promisify } from "node:util";

import { GitRepositoryError, type GitRepositoryPort } from "../domain/branch/git-repository.js";

const execFileAsync = promisify(execFile);

export { GitRepositoryError, type GitRepositoryPort } from "../domain/branch/git-repository.js";

export class GitRepository implements GitRepositoryPort {
  constructor(private readonly cwd = process.cwd()) {}

  async ensureRepository(): Promise<void> {
    try {
      const { stdout } = await this.run(["rev-parse", "--is-inside-work-tree"]);
      if (stdout.trim() !== "true") {
        throw new GitRepositoryError("Git 저장소에서 실행해야 합니다.");
      }
    } catch (error) {
      if (error instanceof GitRepositoryError) {
        throw error;
      }
      throw new GitRepositoryError("Git 저장소에서 실행해야 합니다.");
    }
  }

  async ensureCleanWorkingTree(): Promise<void> {
    const { stdout } = await this.run(["status", "--porcelain"]);
    if (stdout.trim().length > 0) {
      throw new GitRepositoryError("커밋되지 않은 변경 사항이 있어 브랜치를 만들 수 없습니다.");
    }
  }

  async ensureBranchAbsent(branchName: string): Promise<void> {
    const { stdout } = await this.run(["for-each-ref", "--format=%(refname:short)", "refs/heads", "refs/remotes"]);
    const existing = stdout
      .split("\n")
      .map((name) => name.trim())
      .filter(Boolean)
      .some((name) => name === branchName || name.endsWith(`/${branchName}`));

    if (existing) {
      throw new GitRepositoryError(`이미 존재하는 브랜치입니다: ${branchName}`);
    }
  }

  async createAndSwitch(branchName: string): Promise<void> {
    await this.run(["switch", "-c", branchName]);
  }

  async setCurrentTaskNumber(taskNumber: string): Promise<void> {
    await this.run(["config", "--local", "jira-task.current-task", taskNumber]);
  }

  async getCurrentTaskNumber(): Promise<string | undefined> {
    try {
      const result = await execFileAsync("git", ["config", "--local", "--get", "jira-task.current-task"], { cwd: this.cwd });
      const taskNumber = result.stdout.trim();
      return taskNumber.length > 0 ? taskNumber : undefined;
    } catch (error) {
      if (isExitCode(error, 1)) {
        return undefined;
      }
      throw new GitRepositoryError("Git 명령을 실행하지 못했습니다.");
    }
  }

  async ensureStagedChanges(): Promise<void> {
    const { stdout } = await this.run(["diff", "--cached", "--name-only"]);
    if (stdout.trim().length === 0) {
      throw new GitRepositoryError("커밋할 스테이지된 변경 사항이 없습니다.");
    }
  }

  async commit(message: string): Promise<void> {
    await this.run(["commit", "-m", message]);
  }

  private async run(args: string[]): Promise<{ stdout: string }> {
    try {
      const result = await execFileAsync("git", args, { cwd: this.cwd });
      return { stdout: result.stdout };
    } catch {
      throw new GitRepositoryError("Git 명령을 실행하지 못했습니다.");
    }
  }
}

function isExitCode(error: unknown, code: number): boolean {
  return typeof error === "object" && error !== null && "code" in error && (error as { code: unknown }).code === code;
}
