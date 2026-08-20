import { z } from "zod";

import { DEFAULT_WORK_POLICY, type WorkPolicy } from "../policy/work-policy.js";

const requiredText = z.string().trim().min(1, "값을 입력해야 합니다.");

function isValidIsoDate(value: string): boolean {
  const matched = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);

  if (!matched) {
    return false;
  }

  const year = Number(matched[1]);
  const month = Number(matched[2]);
  const day = Number(matched[3]);
  const date = new Date(Date.UTC(year, month - 1, day));

  return (
    date.getUTCFullYear() === year &&
    date.getUTCMonth() === month - 1 &&
    date.getUTCDate() === day
  );
}

const dueDateSchema = requiredText.refine(isValidIsoDate, {
  message: "기한은 유효한 YYYY-MM-DD 형식이어야 합니다.",
});

export const taskDescriptionSchema = z
  .object({
    summary: requiredText,
    deliverables: z.array(requiredText).min(1, "결과물을 하나 이상 입력해야 합니다."),
  })
  .strict();

export function createIssueInputSchema(policy: WorkPolicy) {
  const storyPointSchema = z.custom<number>(
    (value): value is number => typeof value === "number" && policy.storyPoints.includes(value),
    { message: `스토리 포인트는 ${policy.storyPoints.join(", ")} 중 하나여야 합니다.` },
  );

  const epicInputSchema = z
    .object({
      type: z.literal("epic"),
      title: requiredText,
    })
    .strict();

  const storyInputSchema = z
    .object({
      type: z.literal("story"),
      epicKey: requiredText,
      title: requiredText,
      branch: requiredText,
      sprint: requiredText.optional(),
    })
    .strict();

  const taskInputSchema = z
    .object({
      type: z.literal("task"),
      epicKey: requiredText,
      storyKey: requiredText.optional(),
      title: requiredText,
      assignee: requiredText,
      priority: requiredText,
      storyPoints: storyPointSchema,
      sprint: requiredText,
      dueDate: dueDateSchema,
      description: taskDescriptionSchema,
    })
    .strict();

  return z.discriminatedUnion("type", [epicInputSchema, storyInputSchema, taskInputSchema]);
}

export const issueInputSchema = createIssueInputSchema(DEFAULT_WORK_POLICY);
