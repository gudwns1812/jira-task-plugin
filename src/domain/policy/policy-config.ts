import { z } from "zod";

import { DEFAULT_WORK_POLICY, type WorkPolicy } from "./work-policy.js";

const requiredText = z.string().trim().min(1);
const uniqueTextList = z.array(requiredText).min(1).refine((values) => new Set(values).size === values.length, {
  message: "중복된 값은 사용할 수 없습니다.",
});
const uniqueStoryPointList = z
  .array(z.union([z.literal(1), z.literal(2), z.literal(3), z.literal(5), z.literal(8)]))
  .min(1)
  .refine((values) => new Set(values).size === values.length, { message: "중복된 포인트는 사용할 수 없습니다." });
const labelMap = z.record(requiredText, requiredText);

export const policyOverrideSchema = z
  .object({
    areas: uniqueTextList.optional(),
    workCategories: uniqueTextList.optional(),
    storyPoints: uniqueStoryPointList.optional(),
    labels: z
      .object({
        areas: labelMap.optional(),
        categories: labelMap.optional(),
      })
      .strict()
      .optional(),
  })
  .strict();

export type PolicyOverrides = z.infer<typeof policyOverrideSchema>;

export class PolicyConfigurationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "PolicyConfigurationError";
  }
}

export function resolveWorkPolicy(rawOverrides: unknown): WorkPolicy {
  const parsed = policyOverrideSchema.safeParse(rawOverrides);

  if (!parsed.success) {
    const details = parsed.error.issues
      .map((issue) => `${issue.path.map(String).join(".") || "config"}: ${issue.message}`)
      .join("; ");
    throw new PolicyConfigurationError(`정책 설정 형식이 올바르지 않습니다. ${details}`);
  }

  const overrides = parsed.data;
  const areas = overrides.areas ?? DEFAULT_WORK_POLICY.areas;
  const workCategories = overrides.workCategories ?? DEFAULT_WORK_POLICY.workCategories;
  const storyPoints = overrides.storyPoints ?? DEFAULT_WORK_POLICY.storyPoints;

  return {
    areas,
    workCategories,
    storyPoints,
    labels: {
      areas: mergeLabels(DEFAULT_WORK_POLICY.labels.areas, areas, overrides.labels?.areas, "영역"),
      categories: mergeLabels(
        DEFAULT_WORK_POLICY.labels.categories,
        workCategories,
        overrides.labels?.categories,
        "업무 분류",
      ),
    },
  };
}

export function defaultPolicyConfig(): WorkPolicy {
  return resolveWorkPolicy({});
}

function mergeLabels(
  defaults: Readonly<Record<string, string>>,
  allowedKeys: readonly string[],
  overrides: Readonly<Record<string, string>> | undefined,
  kind: string,
): Record<string, string> {
  for (const key of Object.keys(overrides ?? {})) {
    if (!allowedKeys.includes(key)) {
      throw new PolicyConfigurationError(`${kind} 레이블 '${key}'는 설정된 목록에 없습니다.`);
    }
  }

  const applicableDefaults = Object.fromEntries(
    Object.entries(defaults).filter(([key]) => allowedKeys.includes(key)),
  );

  return { ...applicableDefaults, ...overrides };
}
