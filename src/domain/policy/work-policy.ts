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

export function isArea(value: string): value is Area {
  return AREAS.includes(value as Area);
}

export function isWorkCategory(value: string): value is WorkCategory {
  return WORK_CATEGORIES.includes(value as WorkCategory);
}

export function labelForArea(area: Area): string {
  return area === "INFRA" ? "Infra" : area;
}

export function labelForCategory(category: WorkCategory): string | undefined {
  switch (category) {
    case "PLAN":
    case "UX":
    case "DESIGN":
      return "기획";
    case "INFRA":
      return "Infra";
    case "DOCS":
      return "Docs";
    case "QA":
      return "QA";
    case "TECH":
    case "PT":
      return undefined;
  }
}
