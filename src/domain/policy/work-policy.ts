/** `RULE.md`에서 정한, 프로젝트 공통 업무 정책이다. */
export const AREAS = ["FE", "BE", "AI", "QA", "INFRA"] as const;
export const WORK_CATEGORIES = [
  "PLAN",
  "UX",
  "DESIGN",
  "TECH",
  "INFRA",
  "DOCS",
  "QA",
  "PT",
] as const;

/** 8점을 넘는 업무는 생성하지 않고 여러 작업으로 분리한다. */
export const STORY_POINTS = [1, 2, 3, 5, 8] as const;

export type Area = (typeof AREAS)[number];
export type WorkCategory = (typeof WORK_CATEGORIES)[number];
export type StoryPoint = (typeof STORY_POINTS)[number];
