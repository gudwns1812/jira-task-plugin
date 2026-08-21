import { describe, expect, it, vi } from "vitest";

import { startTask } from "../../src/application/start-task.js";
import type { GitRepositoryPort } from "../../src/domain/branch/git-repository.js";

function git(overrides: Partial<GitRepositoryPort> = {}): GitRepositoryPort {
  return {
    ensureRepository: vi.fn().mockResolvedValue(undefined),
    ensureCleanWorkingTree: vi.fn().mockResolvedValue(undefined),
    ensureBranchAbsent: vi.fn().mockResolvedValue(undefined),
    createAndSwitch: vi.fn().mockResolvedValue(undefined),
    setCurrentTaskNumber: vi.fn().mockResolvedValue(undefined),
    getCurrentTaskNumber: vi.fn().mockResolvedValue(undefined),
    ensureStagedChanges: vi.fn().mockResolvedValue(undefined),
    commit: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  };
}

describe("startTask", () => {
  it("유효한 작업 번호를 저장소에 저장한다", async () => {
    const repository = git();

    await expect(startTask("105", repository)).resolves.toEqual({ kind: "success", taskNumber: "105" });
    expect(repository.setCurrentTaskNumber).toHaveBeenCalledWith("105");
  });

  it("잘못된 작업 번호는 Git을 건드리지 않고 거부한다", async () => {
    const repository = git();

    await expect(startTask("0", repository)).resolves.toMatchObject({ kind: "failure", exitCode: 1 });
    expect(repository.ensureRepository).not.toHaveBeenCalled();
    expect(repository.setCurrentTaskNumber).not.toHaveBeenCalled();
  });

  it("Git 저장소가 아니면 실패한다", async () => {
    const repository = git({ ensureRepository: vi.fn().mockRejectedValue(new Error("not a repo")) });

    await expect(startTask("105", repository)).resolves.toMatchObject({ kind: "failure", exitCode: 5 });
    expect(repository.setCurrentTaskNumber).not.toHaveBeenCalled();
  });
});
