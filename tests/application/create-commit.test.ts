import { describe, expect, it, vi } from "vitest";

import { createCommit } from "../../src/application/create-commit.js";
import type { GitRepositoryPort } from "../../src/domain/branch/git-repository.js";

function git(overrides: Partial<GitRepositoryPort> = {}): GitRepositoryPort {
  return {
    ensureRepository: vi.fn().mockResolvedValue(undefined),
    ensureCleanWorkingTree: vi.fn().mockResolvedValue(undefined),
    ensureBranchAbsent: vi.fn().mockResolvedValue(undefined),
    createAndSwitch: vi.fn().mockResolvedValue(undefined),
    setCurrentTaskNumber: vi.fn().mockResolvedValue(undefined),
    getCurrentTaskNumber: vi.fn().mockResolvedValue("105"),
    ensureStagedChanges: vi.fn().mockResolvedValue(undefined),
    commit: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  };
}

const command = { projectKey: "S15P11A108", message: "이메일 회원가입 화면 구현" };

describe("createCommit", () => {
  it("저장된 작업 번호로 이슈 키를 만들어 커밋한다", async () => {
    const repository = git();

    await expect(createCommit(command, repository)).resolves.toEqual({
      kind: "success",
      issueKey: "S15P11A108-105",
      commitMessage: "S15P11A108-105 이메일 회원가입 화면 구현",
    });
    expect(repository.commit).toHaveBeenCalledWith("S15P11A108-105 이메일 회원가입 화면 구현");
  });

  it("현재 작업 번호가 없으면 커밋하지 않는다", async () => {
    const repository = git({ getCurrentTaskNumber: vi.fn().mockResolvedValue(undefined) });

    await expect(createCommit(command, repository)).resolves.toMatchObject({ kind: "failure", exitCode: 1 });
    expect(repository.commit).not.toHaveBeenCalled();
  });

  it("스테이지된 변경 사항이 없으면 커밋하지 않는다", async () => {
    const repository = git({ ensureStagedChanges: vi.fn().mockRejectedValue(new Error("nothing staged")) });

    await expect(createCommit(command, repository)).resolves.toMatchObject({ kind: "failure", exitCode: 5 });
    expect(repository.commit).not.toHaveBeenCalled();
  });

  it("빈 커밋 메시지를 거부한다", async () => {
    const repository = git();

    await expect(createCommit({ ...command, message: "   " }, repository)).resolves.toMatchObject({ kind: "failure", exitCode: 1 });
    expect(repository.commit).not.toHaveBeenCalled();
  });

  it("Git 저장소가 아니면 실패한다", async () => {
    const repository = git({ ensureRepository: vi.fn().mockRejectedValue(new Error("not a repo")) });

    await expect(createCommit(command, repository)).resolves.toMatchObject({ kind: "failure", exitCode: 5 });
    expect(repository.commit).not.toHaveBeenCalled();
  });
});
