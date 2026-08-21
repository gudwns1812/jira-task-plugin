import { describe, expect, it, vi } from "vitest";

import { JiraAuthenticationError, JiraNetworkError, JiraRequestError } from "../../src/domain/jira/story-issue-reader.js";
import { JiraClient } from "../../src/infrastructure/jira-client.js";

describe("JiraClient", () => {
  it("이슈를 읽고 동적으로 찾은 transition ID로 상태 전환 요청을 보낸다", async () => {
    const fetchImplementation = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify({ key: "S15P11A108-100", fields: { issuetype: { name: "Story" }, status: { name: "To Do" } } })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ transitions: [{ id: "31", to: { name: "In Progress" } }] })))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    const client = new JiraClient({ baseUrl: "https://team.atlassian.net", email: "user@example.com", apiToken: "secret", fetchImplementation });

    await expect(client.getIssue("S15P11A108-100")).resolves.toMatchObject({ issueType: "Story", status: "To Do" });
    await expect(client.findTransitionId("S15P11A108-100", "In Progress")).resolves.toBe("31");
    await client.transition("S15P11A108-100", "31");

    expect(fetchImplementation).toHaveBeenLastCalledWith(
      "https://team.atlassian.net/rest/api/3/issue/S15P11A108-100/transitions",
      expect.objectContaining({ method: "POST", body: JSON.stringify({ transition: { id: "31" } }) }),
    );
    expect(JSON.stringify(fetchImplementation.mock.calls)).not.toContain("secret");
  });

  it("인증과 네트워크 오류를 분리하고 자격 증명을 오류에 포함하지 않는다", async () => {
    const authClient = new JiraClient({ baseUrl: "https://team.atlassian.net", email: "user@example.com", apiToken: "secret", fetchImplementation: vi.fn().mockResolvedValue(new Response(null, { status: 401 })) });
    await expect(authClient.getIssue("S15P11A108-100")).rejects.toBeInstanceOf(JiraAuthenticationError);

    const networkClient = new JiraClient({ baseUrl: "https://team.atlassian.net", email: "user@example.com", apiToken: "secret", fetchImplementation: vi.fn().mockRejectedValue(new Error("offline")) });
    await expect(networkClient.getIssue("S15P11A108-100")).rejects.toBeInstanceOf(JiraNetworkError);
  });

  it("연결을 확인하고 페이지를 넘겨 프로젝트와 이슈 유형별 상태를 읽는다", async () => {
    const fetchImplementation = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify({ accountId: "user" })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ values: [{ id: "1", key: "ONE", name: "첫 프로젝트" }], isLast: false, total: 2 })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ values: [{ id: "2", key: "TWO", name: "둘째 프로젝트" }], isLast: true, total: 2 })))
      .mockResolvedValueOnce(new Response(JSON.stringify([{ id: "10", name: "스토리", statuses: [{ id: "3", name: "진행 중" }] }])));
    const client = new JiraClient({ baseUrl: "https://team.atlassian.net", email: "user@example.com", apiToken: "secret", fetchImplementation });

    await expect(client.verifyIdentity()).resolves.toBeUndefined();
    await expect(client.listProjects()).resolves.toEqual([
      { id: "1", key: "ONE", name: "첫 프로젝트" },
      { id: "2", key: "TWO", name: "둘째 프로젝트" },
    ]);
    await expect(client.listIssueTypesWithStatuses("ONE")).resolves.toEqual([
      { id: "10", name: "스토리", statuses: [{ id: "3", name: "진행 중" }] },
    ]);
  });

  it("설정 메타데이터 응답이 깨지면 요청 오류로 끝낸다", async () => {
    const client = new JiraClient({ baseUrl: "https://team.atlassian.net", email: "user@example.com", apiToken: "secret", fetchImplementation: vi.fn().mockResolvedValue(new Response(JSON.stringify({ values: [] }))) });
    await expect(client.listProjects()).rejects.toBeInstanceOf(JiraRequestError);
  });

  it("다음 페이지가 비어 있는데 끝나지 않은 프로젝트 목록은 무한 반복하지 않는다", async () => {
    const client = new JiraClient({ baseUrl: "https://team.atlassian.net", email: "user@example.com", apiToken: "secret", fetchImplementation: vi.fn().mockResolvedValue(new Response(JSON.stringify({ values: [], isLast: false }))) });
    await expect(client.listProjects()).rejects.toBeInstanceOf(JiraRequestError);
  });
});
