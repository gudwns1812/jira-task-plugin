export const ISSUE_TEMPLATE_TYPES = ["story", "task"] as const;

export type IssueTemplateType = (typeof ISSUE_TEMPLATE_TYPES)[number];

const TEMPLATES: Record<IssueTemplateType, string> = {
  story: `예: (S15P11A108-100) [AUTH-001] 회원가입

\`feat/100-signup\``,
  task: `## 설명
작업 설명을 작성합니다.

## 결과물
[] 완료 기준 결과물 1
[] 완료 기준 결과물 2`,
};

export function getIssueTemplate(type: string): string | undefined {
  return type in TEMPLATES ? TEMPLATES[type as IssueTemplateType] : undefined;
}
