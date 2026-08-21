import { describe, expect, it, vi } from "vitest";

import { createBranch } from "../../src/application/create-branch.js";
import { JiraNetworkError, type StoryIssueReader } from "../../src/domain/jira/story-issue-reader.js";
import type { GitRepositoryPort } from "../../src/domain/branch/git-repository.js";

function readers(overrides: Partial<StoryIssueReader> = {}): StoryIssueReader {
  return {
    getIssue: vi.fn().mockResolvedValue({ key: "S15P11A108-100", issueTypeId: "10", issueTypeName: "Story", statusId: "1", statusName: "To Do" }),
    findTransitionId: vi.fn().mockResolvedValue("31"),
    transition: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  };
}

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

const command = {
  projectKey: "S15P11A108",
  storyNumber: "100",
  name: "signup",
  storyIssueTypeId: "10",
  inProgressStatusId: "2",
};

describe("createBranch", () => {
  it("Story를 확인하고 브랜치 생성 뒤 Jira를 진행 중으로 전환한다", async () => {
    const reader = readers();
    const repository = git();

    await expect(createBranch(command, reader, repository)).resolves.toEqual({
      kind: "success",
      branchName: "feat/100-signup",
      storyKey: "S15P11A108-100",
      transitioned: true,
    });
    expect(repository.createAndSwitch).toHaveBeenCalledWith("feat/100-signup");
    expect(reader.findTransitionId).toHaveBeenCalledWith("S15P11A108-100", "2");
    expect(reader.transition).toHaveBeenCalledWith("S15P11A108-100", "31");
  });

  it("Story가 아니면 Git을 변경하지 않는다", async () => {
    const reader = readers({
      getIssue: vi.fn().mockResolvedValue({ key: "S15P11A108-100", issueTypeId: "20", issueTypeName: "Task", statusId: "1", statusName: "To Do" }),
    });
    const repository = git();

    await expect(createBranch(command, reader, repository)).resolves.toMatchObject({
      kind: "failure",
      exitCode: 1,
      message: "S15P11A108-100 이슈는 스토리 타입이 아닙니다. (실제 타입: Task)",
    });
    expect(repository.createAndSwitch).not.toHaveBeenCalled();
  });

  it("이슈 유형 이름이 Jira 응답 엔드포인트마다 다르게 와도 ID로 정확히 비교한다", async () => {
    const reader = readers({
      getIssue: vi.fn().mockResolvedValue({ key: "S15P11A108-100", issueTypeId: "10", issueTypeName: "스토리", statusId: "1", statusName: "할 일" }),
    });
    const repository = git();

    await expect(createBranch(command, reader, repository)).resolves.toMatchObject({ kind: "success" });
  });

  it("이미 진행 중이면 브랜치만 만들고 transition 호출은 건너뛴다", async () => {
    const reader = readers({
      getIssue: vi.fn().mockResolvedValue({ key: "S15P11A108-100", issueTypeId: "10", issueTypeName: "Story", statusId: "2", statusName: "In Progress" }),
    });

    await expect(createBranch(command, reader, git())).resolves.toMatchObject({ kind: "success", transitioned: false });
    expect(reader.findTransitionId).not.toHaveBeenCalled();
    expect(reader.transition).not.toHaveBeenCalled();
  });

  it("Git 안전 검사가 실패하면 Jira 전환을 시도하지 않는다", async () => {
    const reader = readers();
    const repository = git({ ensureCleanWorkingTree: vi.fn().mockRejectedValue(new Error("dirty")) });

    await expect(createBranch(command, reader, repository)).resolves.toMatchObject({ kind: "failure", exitCode: 5 });
    expect(reader.findTransitionId).not.toHaveBeenCalled();
    expect(reader.transition).not.toHaveBeenCalled();
  });

  it("진행 상태 전환 경로가 없으면 브랜치를 보존하는 partial success를 반환한다", async () => {
    const reader = readers({ findTransitionId: vi.fn().mockResolvedValue(undefined) });

    await expect(createBranch(command, reader, git())).resolves.toMatchObject({
      kind: "partial-success",
      branchName: "feat/100-signup",
    });
    expect(reader.transition).not.toHaveBeenCalled();
  });

  it("브랜치 생성 뒤 전환이 실패하면 브랜치를 보존하는 partial success를 반환한다", async () => {
    const reader = readers({ transition: vi.fn().mockRejectedValue(new JiraNetworkError()) });

    await expect(createBranch(command, reader, git())).resolves.toMatchObject({
      kind: "partial-success",
      branchName: "feat/100-signup",
      storyKey: "S15P11A108-100",
    });
  });
});
