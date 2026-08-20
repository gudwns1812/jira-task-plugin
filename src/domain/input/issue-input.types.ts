import type { StoryPoint } from "../policy/work-policy.js";

export type IssueType = "epic" | "story" | "task";

export interface EpicInput {
  type: "epic";
  title: string;
}

export interface StoryInput {
  type: "story";
  epicKey: string;
  title: string;
  branch: string;
  sprint?: string;
}

export interface TaskDescription {
  summary: string;
  deliverables: string[];
}

export interface TaskInput {
  type: "task";
  epicKey: string;
  storyKey?: string;
  title: string;
  assignee: string;
  priority: string;
  storyPoints: StoryPoint;
  sprint: string;
  dueDate: string;
  description: TaskDescription;
}

export type IssueInput = EpicInput | StoryInput | TaskInput;
