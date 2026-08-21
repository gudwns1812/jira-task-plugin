const taskNumberPattern = /^[1-9]\d*$/;

export class TaskNumberError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "TaskNumberError";
  }
}

export function validateTaskNumber(taskNumber: string): void {
  if (!taskNumberPattern.test(taskNumber)) {
    throw new TaskNumberError("작업 번호는 0보다 큰 정수여야 합니다.");
  }
}

export function createTaskKey(projectKey: string, taskNumber: string): string {
  validateTaskNumber(taskNumber);
  return `${projectKey}-${taskNumber}`;
}
