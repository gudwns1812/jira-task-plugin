import { GitRepositoryError, type GitRepositoryPort } from "../domain/branch/git-repository.js";
import { CommitMessageError, createCommitMessage } from "../domain/commit/commit-message.js";
import { createTaskKey, TaskNumberError } from "../domain/task/task-number.js";

export interface CreateCommitCommand {
  projectKey: string;
  message: string;
}

export type CreateCommitResult =
  | { kind: "success"; issueKey: string; commitMessage: string }
  | { kind: "failure"; exitCode: 1 | 5; message: string };

export async function createCommit(
  command: CreateCommitCommand,
  gitRepository: GitRepositoryPort,
): Promise<CreateCommitResult> {
  let taskNumber: string | undefined;
  try {
    await gitRepository.ensureRepository();
    taskNumber = await gitRepository.getCurrentTaskNumber();
  } catch (error) {
    return { kind: "failure", exitCode: 5, message: messageOf(error) };
  }

  if (!taskNumber) {
    return {
      kind: "failure",
      exitCode: 1,
      message: "현재 작업 번호가 없습니다. task start --number <작업 번호>로 저장하세요.",
    };
  }

  let issueKey: string;
  let commitMessage: string;
  try {
    issueKey = createTaskKey(command.projectKey, taskNumber);
    commitMessage = createCommitMessage(issueKey, command.message);
  } catch (error) {
    return { kind: "failure", exitCode: 1, message: messageOf(error) };
  }

  try {
    await gitRepository.ensureStagedChanges();
    await gitRepository.commit(commitMessage);
  } catch (error) {
    return { kind: "failure", exitCode: 5, message: messageOf(error) };
  }

  return { kind: "success", issueKey, commitMessage };
}

function messageOf(error: unknown): string {
  if (error instanceof GitRepositoryError || error instanceof CommitMessageError || error instanceof TaskNumberError || error instanceof Error) {
    return error.message;
  }
  return "예상하지 못한 오류가 발생했습니다.";
}
