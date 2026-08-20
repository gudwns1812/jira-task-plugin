import { describe, expect, it, vi } from "vitest";

import { JiraAuthenticationError, JiraNetworkError } from "../../src/domain/jira/story-issue-reader.js";
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
});
