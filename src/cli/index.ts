#!/usr/bin/env node

import { Command } from "commander";

import { validateRawIssue } from "../application/validate-issue.js";
import { getIssueTemplate } from "../domain/template/issue-template.js";
import { InputFileError, readJsonFile } from "../infrastructure/file-input.js";

const program = new Command();

program
  .name("jira-task")
  .description("RULE.md 기반 Jira 업무 생성 및 검증 CLI")
  .version("0.1.0");

program
  .command("validate")
  .description("JSON 이슈 입력을 RULE.md 기준으로 검증합니다")
  .requiredOption("-f, --file <path>", "검증할 JSON 파일 경로")
  .action(async (options: { file: string }) => {
    try {
      const rawInput = await readJsonFile(options.file);
      const result = validateRawIssue(rawInput);

      printMessages("오류", result.errors, console.error);
      printMessages("경고", result.warnings, console.warn);

      if (result.errors.length > 0) {
        console.error(`\n검증 실패 (${result.errors.length}개 오류)`);
        process.exitCode = 1;
        return;
      }

      console.log("\n검증 성공");
      if (result.derived.labels.length > 0) {
        console.log(`기본 레이블: ${result.derived.labels.join(", ")}`);
      }
    } catch (error) {
      const message = error instanceof InputFileError ? error.message : "입력 파일을 처리하지 못했습니다.";
      console.error(`검증 실패: ${message}`);
      process.exitCode = 1;
    }
  });

program
  .command("template <type>")
  .description("스토리 또는 작업 설명 템플릿을 출력합니다")
  .action((type: string) => {
    const template = getIssueTemplate(type);

    if (!template) {
      console.error("지원하지 않는 템플릿입니다. story 또는 task를 입력하세요.");
      process.exitCode = 1;
      return;
    }

    console.log(template);
  });

await program.parseAsync();

function printMessages(
  heading: string,
  messages: { path: string; message: string }[],
  print: (message: string) => void,
): void {
  if (messages.length === 0) {
    return;
  }

  print(`\n${heading}`);
  messages.forEach((message) => print(`- ${message.path}: ${message.message}`));
}
