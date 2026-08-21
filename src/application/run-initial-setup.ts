import { JiraAuthenticationError } from "../domain/jira/story-issue-reader.js";
import type { JiraIssueTypeWithStatuses, JiraNamedValue, JiraProject, JiraSetupReader } from "../domain/jira/setup-reader.js";
import type { JiraSettings } from "../domain/policy/policy-config.js";

export interface SetupConnection {
  baseUrl: string;
  email: string;
  apiToken: string;
}

export interface SetupChoice {
  value: string;
  label: string;
}

export interface SetupPrompter {
  askConnection(): Promise<SetupConnection>;
  choose(message: string, choices: SetupChoice[]): Promise<string>;
  confirm(summary: SetupSummary): Promise<boolean>;
  showSuccess(message: string): void;
  showError(message: string): void;
}

export interface SetupSummary {
  baseUrl: string;
  project: JiraProject;
  storyIssueType: JiraNamedValue;
  taskIssueType: JiraNamedValue;
  epicIssueType: JiraNamedValue;
  storyStartStatus: JiraNamedValue;
  taskDoneStatus: JiraNamedValue;
}

export interface CredentialStore {
  save(baseUrl: string, email: string, apiToken: string): Promise<void>;
  get(baseUrl: string, email: string): Promise<string | undefined>;
}

export interface InitialSetupDependencies {
  prompter: SetupPrompter;
  createJira(connection: SetupConnection): JiraSetupReader;
  saveConfig(update: { projectKey: string; jira: JiraSettings }): Promise<void>;
  credentials: CredentialStore;
}

export class SetupCancelledError extends Error {
  constructor() {
    super("초기 설정을 취소했습니다.");
    this.name = "SetupCancelledError";
  }
}

export class SetupSelectionError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "SetupSelectionError";
  }
}

export async function runInitialSetup(dependencies: InitialSetupDependencies): Promise<{
  projectKey: string;
  storyIssueType: string;
  storyStartStatus: string;
}> {
  const { connection, jira } = await verifiedConnection(dependencies.prompter, dependencies.createJira);
  dependencies.prompter.showSuccess("✓ Jira 연결 성공");
  const projects = await jira.listProjects();
  if (projects.length === 0) {
    throw new SetupSelectionError("접근할 수 있는 Jira 프로젝트가 없습니다.");
  }

  const project = selectProject(
    projects,
    await dependencies.prompter.choose("프로젝트를 선택하세요.", projects.map((item) => ({ value: item.id, label: `${item.key} - ${item.name}` }))),
  );
  const issueTypes = await jira.listIssueTypesWithStatuses(project.key);
  const storyIssueType = await selectIssueType(dependencies.prompter, "스토리로 사용할 이슈 유형을 선택하세요.", issueTypes, []);
  const taskIssueType = await selectIssueType(dependencies.prompter, "실제 개발 작업으로 사용할 이슈 유형을 선택하세요.", issueTypes, [storyIssueType.id]);
  const epicIssueType = await selectIssueType(dependencies.prompter, "에픽으로 사용할 이슈 유형을 선택하세요.", issueTypes, [storyIssueType.id, taskIssueType.id]);
  const storyStartStatus = await selectStatus(dependencies.prompter, "스토리 브랜치 생성 시 이동할 상태를 선택하세요.", storyIssueType, "start");
  const taskDoneStatus = await selectStatus(dependencies.prompter, "작업 완료 시 이동할 상태를 선택하세요.", taskIssueType, "done");

  const summary: SetupSummary = { baseUrl: connection.baseUrl, project, storyIssueType, taskIssueType, epicIssueType, storyStartStatus, taskDoneStatus };
  if (!(await dependencies.prompter.confirm(summary))) {
    throw new SetupCancelledError();
  }

  const jiraSettings: JiraSettings = {
    baseUrl: connection.baseUrl,
    email: connection.email,
    project: { id: project.id, name: project.name },
    issueTypes: {
      story: toNamedValue(storyIssueType),
      task: toNamedValue(taskIssueType),
      epic: toNamedValue(epicIssueType),
    },
    statuses: {
      story: { start: storyStartStatus },
      task: { done: taskDoneStatus },
    },
  };

  await dependencies.credentials.save(connection.baseUrl, connection.email, connection.apiToken);
  await dependencies.saveConfig({ projectKey: project.key, jira: jiraSettings });
  return { projectKey: project.key, storyIssueType: storyIssueType.name, storyStartStatus: storyStartStatus.name };
}

async function verifiedConnection(
  prompter: SetupPrompter,
  createJira: (connection: SetupConnection) => JiraSetupReader,
): Promise<{ connection: SetupConnection; jira: JiraSetupReader }> {
  while (true) {
    const connection = await prompter.askConnection();
    const jira = createJira(connection);
    try {
      await jira.verifyIdentity();
      return { connection, jira };
    } catch (error) {
      if (!(error instanceof JiraAuthenticationError)) {
        throw error;
      }
      prompter.showError("Jira 인증에 실패했습니다. 이메일과 API 토큰을 다시 확인하세요.");
    }
  }
}

async function selectIssueType(prompter: SetupPrompter, message: string, issueTypes: JiraIssueTypeWithStatuses[], excludedIds: string[]): Promise<JiraIssueTypeWithStatuses> {
  const choices = issueTypes.filter((item) => !excludedIds.includes(item.id));
  if (choices.length === 0) {
    throw new SetupSelectionError("서로 다른 Story, Task, Epic 이슈 유형을 선택할 수 없습니다.");
  }
  const selectedId = await prompter.choose(message, choices.map((item) => ({ value: item.id, label: item.name })));
  const selected = choices.find((item) => item.id === selectedId);
  if (!selected) {
    throw new SetupSelectionError("선택한 이슈 유형이 현재 프로젝트에 없습니다.");
  }
  return selected;
}

async function selectStatus(prompter: SetupPrompter, message: string, issueType: JiraIssueTypeWithStatuses, purpose: string): Promise<JiraNamedValue> {
  if (issueType.statuses.length === 0) {
    throw new SetupSelectionError(`${issueType.name} 이슈 유형에는 ${purpose} 상태를 선택할 수 없습니다.`);
  }
  const selectedId = await prompter.choose(message, issueType.statuses.map((item) => ({ value: item.id, label: item.name })));
  const selected = issueType.statuses.find((item) => item.id === selectedId);
  if (!selected) {
    throw new SetupSelectionError("선택한 상태가 현재 이슈 유형에 없습니다.");
  }
  return selected;
}

function selectProject(projects: JiraProject[], selectedId: string): JiraProject {
  const project = projects.find((item) => item.id === selectedId);
  if (!project) {
    throw new SetupSelectionError("선택한 프로젝트에 접근할 수 없습니다.");
  }
  return project;
}

function toNamedValue(value: JiraIssueTypeWithStatuses): JiraNamedValue {
  return { id: value.id, name: value.name };
}
