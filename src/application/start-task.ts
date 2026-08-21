import { GitRepositoryError, type GitRepositoryPort } from "../domain/branch/git-repository.js";
import { TaskNumberError, validateTaskNumber } from "../domain/task/task-number.js";

export type StartTaskResult =
  | { kind: "success"; taskNumber: string }
  | { kind: "failure"; exitCode: 1 | 5; message: string };

export async function startTask(taskNumber: string, gitRepository: GitRepositoryPort): Promise<StartTaskResult> {
  try {
    validateTaskNumber(taskNumber);
  } catch (error) {
    return { kind: "failure", exitCode: 1, message: messageOf(error) };
  }

  try {
    await gitRepository.ensureRepository();
    await gitRepository.setCurrentTaskNumber(taskNumber);
  } catch (error) {
    return { kind: "failure", exitCode: 5, message: messageOf(error) };
  }

  return { kind: "success", taskNumber };
}

function messageOf(error: unknown): string {
  if (error instanceof GitRepositoryError || error instanceof TaskNumberError || error instanceof Error) {
    return error.message;
  }
  return "예상하지 못한 오류가 발생했습니다.";
}
