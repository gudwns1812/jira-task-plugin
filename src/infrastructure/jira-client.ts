import {
  JiraAuthenticationError,
  JiraNetworkError,
  JiraRequestError,
  type JiraIssue,
  type StoryIssueReader,
} from "../domain/jira/story-issue-reader.js";

export interface JiraClientOptions {
  baseUrl: string;
  email: string;
  apiToken: string;
  fetchImplementation?: typeof fetch;
}

export class JiraClient implements StoryIssueReader {
  private readonly baseUrl: string;
  private readonly authorization: string;
  private readonly fetchImplementation: typeof fetch;

  constructor(options: JiraClientOptions) {
    this.baseUrl = options.baseUrl.replace(/\/$/, "");
    this.authorization = `Basic ${Buffer.from(`${options.email}:${options.apiToken}`).toString("base64")}`;
    this.fetchImplementation = options.fetchImplementation ?? fetch;
  }

  async getIssue(issueKey: string): Promise<JiraIssue> {
    const response = await this.request(`/rest/api/3/issue/${encodeURIComponent(issueKey)}?fields=issuetype,status`);
    const body = (await response.json()) as {
      key?: unknown;
      fields?: { issuetype?: { name?: unknown }; status?: { name?: unknown } };
    };
    const issueType = body.fields?.issuetype?.name;
    const status = body.fields?.status?.name;

    if (typeof body.key !== "string" || typeof issueType !== "string" || typeof status !== "string") {
      throw new JiraRequestError("Jira 이슈 응답 형식이 올바르지 않습니다.");
    }

    return { key: body.key, issueType, status };
  }

  async findTransitionId(issueKey: string, targetStatus: string): Promise<string | undefined> {
    const response = await this.request(`/rest/api/3/issue/${encodeURIComponent(issueKey)}/transitions`);
    const body = (await response.json()) as { transitions?: unknown };
    if (!Array.isArray(body.transitions)) {
      throw new JiraRequestError("Jira 전환 응답 형식이 올바르지 않습니다.");
    }

    for (const transition of body.transitions) {
      if (
        typeof transition === "object" &&
        transition !== null &&
        "id" in transition &&
        "to" in transition &&
        typeof transition.id === "string" &&
        typeof transition.to === "object" &&
        transition.to !== null &&
        "name" in transition.to &&
        transition.to.name === targetStatus
      ) {
        return transition.id;
      }
    }

    return undefined;
  }

  async transition(issueKey: string, transitionId: string): Promise<void> {
    await this.request(`/rest/api/3/issue/${encodeURIComponent(issueKey)}/transitions`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ transition: { id: transitionId } }),
    });
  }

  private async request(path: string, init: RequestInit = {}): Promise<Response> {
    let response: Response;
    try {
      response = await this.fetchImplementation(`${this.baseUrl}${path}`, {
        ...init,
        headers: { Accept: "application/json", Authorization: this.authorization, ...init.headers },
      });
    } catch {
      throw new JiraNetworkError();
    }

    if (response.ok) {
      return response;
    }
    if (response.status === 401 || response.status === 403) {
      throw new JiraAuthenticationError();
    }
    if (response.status >= 500) {
      throw new JiraNetworkError();
    }
    if (response.status === 404) {
      throw new JiraRequestError("Jira 이슈 또는 전환을 찾을 수 없습니다.");
    }
    throw new JiraRequestError("Jira 요청이 워크플로 규칙을 만족하지 않습니다.");
  }
}
