#!/usr/bin/env node

import { Command } from "commander";

import { validateRawIssue } from "../application/validate-issue.js";
import { PolicyConfigurationError } from "../domain/policy/policy-config.js";
import { getIssueTemplate } from "../domain/template/issue-template.js";
import { InputFileError, readJsonFile } from "../infrastructure/file-input.js";
import {
  initializePolicyConfig,
  loadWorkPolicy,
} from "../infrastructure/policy-config-repository.js";

const program = new Command();

program
  .name("jira-task")
  .description("RULE.md 기반 Jira 업무 생성 및 검증 CLI")
  .version("0.1.0");

program
  .command("validate")
  .description("JSON 이슈 입력을 RULE.md 기준으로 검증합니다")
  .requiredOption("-f, --file <path>", "검증할 JSON 파일 경로")
  .option("-c, --config <path>", "기본 정책을 덮어쓸 JSON 설정 파일")
  .action(async (options: { file: string; config?: string }) => {
    try {
      const rawInput = await readJsonFile(options.file);
      const policy = await loadWorkPolicy(options.config);
      const result = validateRawIssue(rawInput, policy);

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
      const message =
        error instanceof InputFileError || error instanceof PolicyConfigurationError
          ? error.message
          : "입력 파일을 처리하지 못했습니다.";
      console.error(`검증 실패: ${message}`);
      process.exitCode = 1;
    }
  });

const configCommand = program.command("config").description("업무 정책 설정을 관리합니다");

configCommand
  .command("init")
  .description("편집 가능한 기본 정책 설정 파일을 만듭니다")
  .option("-c, --config <path>", "만들 정책 설정 파일 경로")
  .action(async (options: { config?: string }) => {
    try {
      const path = await initializePolicyConfig(options.config);
      console.log(`정책 설정 파일을 만들었습니다: ${path}`);
    } catch (error) {
      const message = error instanceof PolicyConfigurationError ? error.message : "정책 설정 파일을 만들지 못했습니다.";
      console.error(`설정 실패: ${message}`);
      process.exitCode = 1;
    }
  });

configCommand
  .command("show")
  .description("현재 적용되는 업무 정책을 출력합니다")
  .option("-c, --config <path>", "읽을 정책 설정 파일 경로")
  .action(async (options: { config?: string }) => {
    try {
      console.log(JSON.stringify(await loadWorkPolicy(options.config), null, 2));
    } catch (error) {
      const message = error instanceof PolicyConfigurationError ? error.message : "정책 설정을 읽지 못했습니다.";
      console.error(`설정 실패: ${message}`);
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
