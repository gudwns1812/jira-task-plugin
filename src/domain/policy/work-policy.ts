/** `RULE.md`를 코드로 옮긴 기본 업무 정책이다. */
export interface WorkPolicy {
  areas: readonly string[];
  workCategories: readonly string[];
  storyPoints: readonly number[];
  labels: {
    areas: Readonly<Record<string, string>>;
    categories: Readonly<Record<string, string>>;
  };
}

export const DEFAULT_WORK_POLICY: WorkPolicy = {
  areas: ["FE", "BE", "AI", "QA", "INFRA"],
  workCategories: ["PLAN", "UX", "DESIGN", "TECH", "INFRA", "DOCS", "QA", "PT"],
  storyPoints: [1, 2, 3, 5, 8],
  labels: {
    areas: { INFRA: "Infra" },
    categories: {
      PLAN: "기획",
      UX: "기획",
      DESIGN: "기획",
      INFRA: "Infra",
      DOCS: "Docs",
      QA: "QA",
    },
  },
};

/** 이전 코드와 기본 정책 테스트에서 사용하는 편의 상수다. */
export const AREAS = DEFAULT_WORK_POLICY.areas;
export const WORK_CATEGORIES = DEFAULT_WORK_POLICY.workCategories;
export const STORY_POINTS = DEFAULT_WORK_POLICY.storyPoints;

export type Area = string;
export type WorkCategory = string;
export type StoryPoint = number;

export function isArea(policy: WorkPolicy, value: string): boolean {
  return policy.areas.includes(value);
}

export function isWorkCategory(policy: WorkPolicy, value: string): boolean {
  return policy.workCategories.includes(value);
}

export function labelForArea(policy: WorkPolicy, area: string): string {
  return policy.labels.areas[area] ?? area;
}

export function labelForCategory(policy: WorkPolicy, category: string): string | undefined {
  return policy.labels.categories[category];
}
