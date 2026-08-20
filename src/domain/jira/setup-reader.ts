export interface JiraProject {
  id: string;
  key: string;
  name: string;
}

export interface JiraNamedValue {
  id: string;
  name: string;
}

export interface JiraIssueTypeWithStatuses extends JiraNamedValue {
  statuses: JiraNamedValue[];
}

/** 최초 설정에 필요한 읽기 전용 Jira 메타데이터 경계다. */
export interface JiraSetupReader {
  verifyIdentity(): Promise<void>;
  listProjects(): Promise<JiraProject[]>;
  listIssueTypesWithStatuses(projectKey: string): Promise<JiraIssueTypeWithStatuses[]>;
}
