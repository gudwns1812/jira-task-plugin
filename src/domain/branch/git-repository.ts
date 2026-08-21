export interface GitRepositoryPort {
  ensureRepository(): Promise<void>;
  ensureCleanWorkingTree(): Promise<void>;
  ensureBranchAbsent(branchName: string): Promise<void>;
  createAndSwitch(branchName: string): Promise<void>;
  setCurrentTaskNumber(taskNumber: string): Promise<void>;
  getCurrentTaskNumber(): Promise<string | undefined>;
  ensureStagedChanges(): Promise<void>;
  commit(message: string): Promise<void>;
}

export class GitRepositoryError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "GitRepositoryError";
  }
}
