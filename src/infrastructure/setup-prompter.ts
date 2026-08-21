import { confirm, input, password, select } from "@inquirer/prompts";

import type { SetupChoice, SetupConnection, SetupPrompter, SetupSummary } from "../application/run-initial-setup.js";

export class InquirerSetupPrompter implements SetupPrompter {
  async askConnection(): Promise<SetupConnection> {
    const baseUrl = await input({
      message: "Jira 주소:",
      validate: (value) => isJiraUrl(value) || "https://team.atlassian.net 형식으로 입력하세요.",
    });
    const email = await input({
      message: "Jira 이메일:",
      validate: (value) => value.trim().length > 0 || "Jira 이메일을 입력하세요.",
    });
    const apiToken = await password({
      message: "Jira API Token:",
      mask: "*",
      validate: (value) => value.length > 0 || "Jira API Token을 입력하세요.",
    });
    return { baseUrl: baseUrl.replace(/\/$/, ""), email: email.trim(), apiToken };
  }

  async choose(message: string, choices: SetupChoice[]): Promise<string> {
    return select({ message, choices: choices.map((choice) => ({ name: choice.label, value: choice.value })) });
  }

  async confirm(summary: SetupSummary): Promise<boolean> {
    console.log("\n설정 내용을 확인해주세요.\n");
    console.log(`Jira       ${summary.baseUrl}`);
    console.log(`Project    ${summary.project.key} - ${summary.project.name}`);
    console.log(`Story      ${summary.storyIssueType.name}`);
    console.log(`Task       ${summary.taskIssueType.name}`);
    console.log(`Epic       ${summary.epicIssueType.name}`);
    console.log(`Start (Story) ${summary.storyStartStatus.name}`);
    console.log(`Done (Task)   ${summary.taskDoneStatus.name}\n`);
    return confirm({ message: "저장하시겠습니까?", default: true });
  }

  showError(message: string): void {
    console.error(`\n${message}\n`);
  }

  showSuccess(message: string): void {
    console.log(`\n${message}\n`);
  }
}

function isJiraUrl(value: string): boolean {
  try {
    const parsed = new URL(value);
    return parsed.protocol === "https:" || parsed.protocol === "http:";
  } catch {
    return false;
  }
}
