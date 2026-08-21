import { execFile } from "node:child_process";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { promisify } from "node:util";

import { describe, expect, it } from "vitest";

import { GitRepository, GitRepositoryError } from "../../src/infrastructure/git-repository.js";

const execFileAsync = promisify(execFile);

describe("GitRepository", () => {
  it("깨끗한 임시 저장소에서 새 브랜치를 만들고 전환한다", async () => {
    const directory = await createRepository();

    try {
      const repository = new GitRepository(directory);
      await repository.ensureRepository();
      await repository.ensureCleanWorkingTree();
      await repository.ensureBranchAbsent("feat/100-signup");
      await repository.createAndSwitch("feat/100-signup");

      await expect(execFileAsync("git", ["branch", "--show-current"], { cwd: directory })).resolves.toMatchObject({ stdout: "feat/100-signup\n" });
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  });

  it("더러운 작업 트리와 중복 브랜치를 거부한다", async () => {
    const directory = await createRepository();

    try {
      const repository = new GitRepository(directory);
      await writeFile(join(directory, "dirty.txt"), "dirty");
      await expect(repository.ensureCleanWorkingTree()).rejects.toBeInstanceOf(GitRepositoryError);

      await execFileAsync("git", ["switch", "-c", "feat/100-signup"], { cwd: directory });
      await expect(repository.ensureBranchAbsent("feat/100-signup")).rejects.toBeInstanceOf(GitRepositoryError);
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  });

  it("현재 작업 번호를 저장소별로 저장하고 읽는다", async () => {
    const directory = await createRepository();

    try {
      const repository = new GitRepository(directory);
      await expect(repository.getCurrentTaskNumber()).resolves.toBeUndefined();

      await repository.setCurrentTaskNumber("105");
      await expect(repository.getCurrentTaskNumber()).resolves.toBe("105");

      await repository.setCurrentTaskNumber("210");
      await expect(repository.getCurrentTaskNumber()).resolves.toBe("210");
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  });

  it("스테이지된 변경 사항 여부를 확인하고 커밋한다", async () => {
    const directory = await createRepository();

    try {
      const repository = new GitRepository(directory);
      await expect(repository.ensureStagedChanges()).rejects.toBeInstanceOf(GitRepositoryError);

      await writeFile(join(directory, "feature.txt"), "feature");
      await execFileAsync("git", ["add", "feature.txt"], { cwd: directory });
      await expect(repository.ensureStagedChanges()).resolves.toBeUndefined();

      await repository.commit("S15P11A108-105 기능 추가");
      await expect(execFileAsync("git", ["log", "-1", "--pretty=%s"], { cwd: directory })).resolves.toMatchObject({
        stdout: "S15P11A108-105 기능 추가\n",
      });
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  });
});

async function createRepository(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), "task-jira-cli-git-"));
  await execFileAsync("git", ["init"], { cwd: directory });
  await execFileAsync("git", ["config", "user.email", "test@example.com"], { cwd: directory });
  await execFileAsync("git", ["config", "user.name", "Test"], { cwd: directory });
  await writeFile(join(directory, "README.md"), "test\n");
  await execFileAsync("git", ["add", "README.md"], { cwd: directory });
  await execFileAsync("git", ["commit", "-m", "initial"], { cwd: directory });
  return directory;
}
