export interface JiraIssue {
  key: string;
  issueTypeId: string;
  issueTypeName: string;
  statusId: string;
  statusName: string;
}

export interface StoryIssueReader {
  getIssue(issueKey: string): Promise<JiraIssue>;
  findTransitionId(issueKey: string, targetStatusId: string): Promise<string | undefined>;
  transition(issueKey: string, transitionId: string): Promise<void>;
}

export class JiraAuthenticationError extends Error {
  constructor(message = "Jira 인증 또는 권한을 확인할 수 없습니다.") {
    super(message);
    this.name = "JiraAuthenticationError";
  }
}

export class JiraNetworkError extends Error {
  constructor(message = "Jira 서버와 통신할 수 없습니다.") {
    super(message);
    this.name = "JiraNetworkError";
  }
}

export class JiraRequestError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "JiraRequestError";
  }
}
