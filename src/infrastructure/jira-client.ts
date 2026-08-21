import {
  JiraAuthenticationError,
  JiraNetworkError,
  JiraRequestError,
  type JiraIssue,
  type StoryIssueReader,
} from "../domain/jira/story-issue-reader.js";
import type { JiraIssueTypeWithStatuses, JiraProject, JiraSetupReader } from "../domain/jira/setup-reader.js";

export interface JiraClientOptions {
  baseUrl: string;
  email: string;
  apiToken: string;
  fetchImplementation?: typeof fetch;
}

export class JiraClient implements StoryIssueReader, JiraSetupReader {
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
      fields?: { issuetype?: { id?: unknown; name?: unknown }; status?: { id?: unknown; name?: unknown } };
    };
    const issueTypeId = body.fields?.issuetype?.id;
    const issueTypeName = body.fields?.issuetype?.name;
    const statusId = body.fields?.status?.id;
    const statusName = body.fields?.status?.name;

    if (
      typeof body.key !== "string" ||
      typeof issueTypeId !== "string" ||
      typeof issueTypeName !== "string" ||
      typeof statusId !== "string" ||
      typeof statusName !== "string"
    ) {
      throw new JiraRequestError("Jira 이슈 응답 형식이 올바르지 않습니다.");
    }

    return { key: body.key, issueTypeId, issueTypeName, statusId, statusName };
  }

  async verifyIdentity(): Promise<void> {
    await this.request("/rest/api/3/myself");
  }

  async listProjects(): Promise<JiraProject[]> {
    const projects: JiraProject[] = [];
    let startAt = 0;

    while (true) {
      const response = await this.request(`/rest/api/3/project/search?startAt=${startAt}&maxResults=50`);
      const body = (await response.json()) as { values?: unknown; isLast?: unknown };
      if (!Array.isArray(body.values) || typeof body.isLast !== "boolean") {
        throw new JiraRequestError("Jira 프로젝트 목록 응답 형식이 올바르지 않습니다.");
      }
      if (!body.isLast && body.values.length === 0) {
        throw new JiraRequestError("Jira 프로젝트 목록의 다음 페이지를 확인할 수 없습니다.");
      }
      for (const project of body.values) {
        if (
          typeof project !== "object" ||
          project === null ||
          !("id" in project) ||
          !("key" in project) ||
          !("name" in project) ||
          typeof project.id !== "string" ||
          typeof project.key !== "string" ||
          typeof project.name !== "string"
        ) {
          throw new JiraRequestError("Jira 프로젝트 목록 응답 형식이 올바르지 않습니다.");
        }
        projects.push({ id: project.id, key: project.key, name: project.name });
      }
      if (body.isLast) {
        return projects;
      }
      startAt += 50;
    }
  }

  async listIssueTypesWithStatuses(projectKey: string): Promise<JiraIssueTypeWithStatuses[]> {
    const response = await this.request(`/rest/api/3/project/${encodeURIComponent(projectKey)}/statuses`);
    const body = (await response.json()) as unknown;
    if (!Array.isArray(body)) {
      throw new JiraRequestError("Jira 이슈 유형 및 상태 응답 형식이 올바르지 않습니다.");
    }
    return body.map((issueType) => decodeIssueTypeWithStatuses(issueType));
  }

  async findTransitionId(issueKey: string, targetStatusId: string): Promise<string | undefined> {
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
        "id" in transition.to &&
        transition.to.id === targetStatusId
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

function decodeIssueTypeWithStatuses(value: unknown): JiraIssueTypeWithStatuses {
  if (typeof value !== "object" || value === null || !("id" in value) || !("name" in value) || !("statuses" in value)) {
    throw new JiraRequestError("Jira 이슈 유형 및 상태 응답 형식이 올바르지 않습니다.");
  }
  if (typeof value.id !== "string" || typeof value.name !== "string" || !Array.isArray(value.statuses)) {
    throw new JiraRequestError("Jira 이슈 유형 및 상태 응답 형식이 올바르지 않습니다.");
  }
  const statuses = value.statuses.map((status) => {
    if (typeof status !== "object" || status === null || !("id" in status) || !("name" in status) || typeof status.id !== "string" || typeof status.name !== "string") {
      throw new JiraRequestError("Jira 이슈 유형 및 상태 응답 형식이 올바르지 않습니다.");
    }
    return { id: status.id, name: status.name };
  });
  return { id: value.id, name: value.name, statuses };
}
