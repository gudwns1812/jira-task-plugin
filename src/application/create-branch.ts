import { createBranchName, createStoryKey, BranchNameError } from "../domain/branch/branch-name.js";
import { GitRepositoryError, type GitRepositoryPort } from "../domain/branch/git-repository.js";
import {
  JiraAuthenticationError,
  JiraNetworkError,
  JiraRequestError,
  type StoryIssueReader,
} from "../domain/jira/story-issue-reader.js";

export interface CreateBranchCommand {
  projectKey: string;
  storyNumber: string;
  name: string;
  storyIssueType: string;
  inProgressStatus: string;
}

export type CreateBranchResult =
  | { kind: "success"; branchName: string; storyKey: string; transitioned: boolean }
  | { kind: "partial-success"; branchName: string; storyKey: string; message: string }
  | { kind: "failure"; exitCode: 1 | 3 | 4 | 5; message: string };

export async function createBranch(
  command: CreateBranchCommand,
  storyIssueReader: StoryIssueReader,
  gitRepository: GitRepositoryPort,
): Promise<CreateBranchResult> {
  let storyKey: string;
  let branchName: string;
  try {
    storyKey = createStoryKey(command.projectKey, command.storyNumber);
    branchName = createBranchName(command.storyNumber, command.name);
  } catch (error) {
    return { kind: "failure", exitCode: 1, message: messageOf(error) };
  }

  let issue;
  try {
    issue = await storyIssueReader.getIssue(storyKey);
  } catch (error) {
    return toPreBranchJiraFailure(error);
  }

  if (issue.issueType !== command.storyIssueType) {
    return { kind: "failure", exitCode: 1, message: `${storyKey} 이슈는 ${command.storyIssueType} 타입이 아닙니다.` };
  }

  try {
    await gitRepository.ensureRepository();
    await gitRepository.ensureCleanWorkingTree();
    await gitRepository.ensureBranchAbsent(branchName);
    await gitRepository.createAndSwitch(branchName);
  } catch (error) {
    return { kind: "failure", exitCode: 5, message: messageOf(error) };
  }

  if (issue.status === command.inProgressStatus) {
    return { kind: "success", branchName, storyKey, transitioned: false };
  }

  try {
    const transitionId = await storyIssueReader.findTransitionId(storyKey, command.inProgressStatus);
    if (!transitionId) {
      return partialSuccess(branchName, storyKey, "진행 중 상태로 전환할 수 있는 Jira 워크플로를 찾지 못했습니다.");
    }
    await storyIssueReader.transition(storyKey, transitionId);
    return { kind: "success", branchName, storyKey, transitioned: true };
  } catch (error) {
    return partialSuccess(branchName, storyKey, `Jira 스토리 상태를 진행 중으로 바꾸지 못했습니다: ${messageOf(error)}`);
  }
}

function toPreBranchJiraFailure(error: unknown): CreateBranchResult {
  if (error instanceof JiraAuthenticationError) {
    return { kind: "failure", exitCode: 3, message: error.message };
  }
  if (error instanceof JiraNetworkError) {
    return { kind: "failure", exitCode: 4, message: error.message };
  }
  if (error instanceof JiraRequestError) {
    return { kind: "failure", exitCode: 1, message: error.message };
  }
  return { kind: "failure", exitCode: 4, message: "Jira 이슈를 조회하지 못했습니다." };
}

function partialSuccess(branchName: string, storyKey: string, message: string): CreateBranchResult {
  return { kind: "partial-success", branchName, storyKey, message };
}

function messageOf(error: unknown): string {
  if (error instanceof BranchNameError || error instanceof GitRepositoryError || error instanceof Error) {
    return error.message;
  }
  return "예상하지 못한 오류가 발생했습니다.";
}
