export class CommitMessageError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "CommitMessageError";
  }
}

export function createCommitMessage(issueKey: string, message: string): string {
  const trimmed = message.trim();
  if (!trimmed) {
    throw new CommitMessageError("커밋 메시지를 입력하세요.");
  }

  return `${issueKey} ${trimmed}`;
}
