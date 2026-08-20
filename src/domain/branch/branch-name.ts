const storyNumberPattern = /^[1-9]\d*$/;
const branchSlugPattern = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;

export class BranchNameError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "BranchNameError";
  }
}

export function createStoryKey(projectKey: string, storyNumber: string): string {
  if (!storyNumberPattern.test(storyNumber)) {
    throw new BranchNameError("스토리 번호는 0보다 큰 정수여야 합니다.");
  }

  return `${projectKey}-${storyNumber}`;
}

export function createBranchName(storyNumber: string, name: string): string {
  if (!storyNumberPattern.test(storyNumber)) {
    throw new BranchNameError("스토리 번호는 0보다 큰 정수여야 합니다.");
  }

  if (!branchSlugPattern.test(name)) {
    throw new BranchNameError("브랜치 이름은 소문자 영문·숫자·하이픈만 사용하고 앞뒤 또는 연속 하이픈을 둘 수 없습니다.");
  }

  return `feat/${storyNumber}-${name}`;
}
