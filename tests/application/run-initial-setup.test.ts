import { describe, expect, it, vi } from "vitest";

import { runInitialSetup, SetupCancelledError, SetupSelectionError } from "../../src/application/run-initial-setup.js";
import { JiraAuthenticationError } from "../../src/domain/jira/story-issue-reader.js";

describe("최초 Jira 설정", () => {
  it("Jira 메타데이터를 선택해 비밀 없이 설정을 저장하고 토큰은 자격 증명 저장소에 보관한다", async () => {
    const prompter = prompts({ confirm: true });
    const saveConfig = vi.fn().mockResolvedValue(undefined);
    const saveCredential = vi.fn().mockResolvedValue(undefined);

    await expect(
      runInitialSetup(
        { prompter, createJira: () => jiraReader(), saveConfig, credentials: { save: saveCredential } },
      ),
    ).resolves.toMatchObject({ projectKey: "S15P11A108", storyIssueType: "스토리", storyStartStatus: "진행 중" });

    expect(saveConfig).toHaveBeenCalledWith({
      projectKey: "S15P11A108",
      jira: {
        baseUrl: "https://team.atlassian.net",
        email: "user@example.com",
        project: { id: "10001", name: "특화 프로젝트" },
        issueTypes: {
          story: { id: "10", name: "스토리" },
          task: { id: "11", name: "작업" },
          epic: { id: "12", name: "에픽" },
        },
        statuses: {
          story: { start: { id: "3", name: "진행 중" } },
          task: { done: { id: "4", name: "완료" } },
        },
      },
    });
    expect(saveCredential).toHaveBeenCalledWith("https://team.atlassian.net", "user@example.com", "secret");
    expect(prompter.showSuccess).toHaveBeenCalledWith("✓ Jira 연결 성공");
    expect(JSON.stringify(saveConfig.mock.calls)).not.toContain("secret");
  });

  it("인증 실패 후 자격 증명을 다시 받아 성공하며 실패한 토큰을 저장하지 않는다", async () => {
    const prompter = prompts({ credentials: ["wrong", "secret"], confirm: true });
    const verifyIdentity = vi
      .fn()
      .mockRejectedValueOnce(new JiraAuthenticationError())
      .mockResolvedValueOnce(undefined);
    const saveCredential = vi.fn().mockResolvedValue(undefined);

    await runInitialSetup({
      prompter,
      createJira: () => ({ ...jiraReader(), verifyIdentity }),
      saveConfig: vi.fn().mockResolvedValue(undefined),
      credentials: { save: saveCredential },
    });

    expect(prompter.showError).toHaveBeenCalledWith("Jira 인증에 실패했습니다. 이메일과 API 토큰을 다시 확인하세요.");
    expect(saveCredential).toHaveBeenCalledTimes(1);
    expect(saveCredential).toHaveBeenLastCalledWith("https://team.atlassian.net", "user@example.com", "secret");
  });

  it("확인을 거절하거나 선택이 유효하지 않으면 설정과 토큰을 저장하지 않는다", async () => {
    const saveConfig = vi.fn().mockResolvedValue(undefined);
    const saveCredential = vi.fn().mockResolvedValue(undefined);

    await expect(
      runInitialSetup({ prompter: prompts({ confirm: false }), createJira: () => jiraReader(), saveConfig, credentials: { save: saveCredential } }),
    ).rejects.toBeInstanceOf(SetupCancelledError);
    expect(saveConfig).not.toHaveBeenCalled();
    expect(saveCredential).not.toHaveBeenCalled();

    await expect(
      runInitialSetup({
        prompter: prompts({ selections: ["10001", "999", "4"], confirm: true }),
        createJira: () => jiraReader(),
        saveConfig,
        credentials: { save: saveCredential },
      }),
    ).rejects.toBeInstanceOf(SetupSelectionError);
  });

  it("스토리/작업/에픽 이슈 유형은 영어 이름도 자동으로 찾는다", async () => {
    const saveConfig = vi.fn().mockResolvedValue(undefined);

    await expect(
      runInitialSetup({
        prompter: prompts({ confirm: true }),
        createJira: () => ({
          ...jiraReader(),
          listIssueTypesWithStatuses: vi.fn().mockResolvedValue([
            { id: "10", name: "Story", statuses: [{ id: "3", name: "진행 중" }] },
            { id: "11", name: "Task", statuses: [{ id: "4", name: "완료" }] },
            { id: "12", name: "Epic", statuses: [{ id: "5", name: "해야 할 일" }] },
          ]),
        }),
        saveConfig,
        credentials: { save: vi.fn().mockResolvedValue(undefined) },
      }),
    ).resolves.toMatchObject({ storyIssueType: "Story" });
    expect(saveConfig).toHaveBeenCalled();
  });

  it.each([
    ["스토리", [{ id: "11", name: "작업" }, { id: "12", name: "에픽" }]],
    ["작업", [{ id: "10", name: "스토리" }, { id: "12", name: "에픽" }]],
    ["에픽", [{ id: "10", name: "스토리" }, { id: "11", name: "작업" }]],
  ])("%s 이슈 유형이 프로젝트에 없으면 묻지 않고 바로 실패한다", async (_label, availableTypes) => {
    const saveConfig = vi.fn().mockResolvedValue(undefined);

    await expect(
      runInitialSetup({
        prompter: prompts({ confirm: true }),
        createJira: () => ({
          ...jiraReader(),
          listIssueTypesWithStatuses: vi
            .fn()
            .mockResolvedValue(availableTypes.map((item) => ({ ...item, statuses: [{ id: "4", name: "완료" }] }))),
        }),
        saveConfig,
        credentials: { save: vi.fn().mockResolvedValue(undefined) },
      }),
    ).rejects.toBeInstanceOf(SetupSelectionError);
    expect(saveConfig).not.toHaveBeenCalled();
  });

  it("이름이 겹치는 이슈 유형이 여러 개면 묻지 않고 바로 실패한다", async () => {
    const saveConfig = vi.fn().mockResolvedValue(undefined);

    await expect(
      runInitialSetup({
        prompter: prompts({ confirm: true }),
        createJira: () => ({
          ...jiraReader(),
          listIssueTypesWithStatuses: vi.fn().mockResolvedValue([
            { id: "10", name: "스토리", statuses: [{ id: "3", name: "진행 중" }] },
            { id: "13", name: "Story", statuses: [{ id: "3", name: "진행 중" }] },
            { id: "11", name: "작업", statuses: [{ id: "4", name: "완료" }] },
            { id: "12", name: "에픽", statuses: [{ id: "5", name: "해야 할 일" }] },
          ]),
        }),
        saveConfig,
        credentials: { save: vi.fn().mockResolvedValue(undefined) },
      }),
    ).rejects.toBeInstanceOf(SetupSelectionError);
    expect(saveConfig).not.toHaveBeenCalled();
  });
});

function prompts(options: { credentials?: string[]; selections?: string[]; confirm: boolean }) {
  const credentials = options.credentials ?? ["secret"];
  const selections = options.selections ?? ["10001", "3", "4"];
  let credentialIndex = 0;
  let selectionIndex = 0;

  return {
    askConnection: vi.fn().mockImplementation(async () => ({
      baseUrl: "https://team.atlassian.net",
      email: "user@example.com",
      apiToken: credentials[credentialIndex++],
    })),
    choose: vi.fn().mockImplementation(async () => selections[selectionIndex++]),
    confirm: vi.fn().mockResolvedValue(options.confirm),
    showError: vi.fn(),
    showSuccess: vi.fn(),
  };
}

function jiraReader() {
  return {
    verifyIdentity: vi.fn().mockResolvedValue(undefined),
    listProjects: vi.fn().mockResolvedValue([{ id: "10001", key: "S15P11A108", name: "특화 프로젝트" }]),
    listIssueTypesWithStatuses: vi.fn().mockResolvedValue([
      { id: "10", name: "스토리", statuses: [{ id: "3", name: "진행 중" }] },
      { id: "11", name: "작업", statuses: [{ id: "4", name: "완료" }] },
      { id: "12", name: "에픽", statuses: [{ id: "5", name: "해야 할 일" }] },
    ]),
  };
}
