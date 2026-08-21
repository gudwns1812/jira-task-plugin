#!/usr/bin/env node

import { Command } from "commander";

import { createBranch } from "../application/create-branch.js";
import { createCommit } from "../application/create-commit.js";
import { createDraft } from "../application/create-draft.js";
import { runInitialSetup, SetupCancelledError, SetupSelectionError } from "../application/run-initial-setup.js";
import { startTask } from "../application/start-task.js";
import { validateRawIssue } from "../application/validate-issue.js";
import { PolicyConfigurationError, resolveJiraTaskConfig } from "../domain/policy/policy-config.js";
import { getIssueTemplate } from "../domain/template/issue-template.js";
import { InputFileError, readJsonFile } from "../infrastructure/file-input.js";
import { GitRepository } from "../infrastructure/git-repository.js";
import { JiraClient } from "../infrastructure/jira-client.js";
import { CredentialStorageError, KeyringCredentialStore } from "../infrastructure/keyring-credential-store.js";
import {
  initializePolicyConfig,
  hasJiraTaskConfig,
  loadJiraTaskConfig,
  loadWorkPolicy,
  setJiraTaskConfig,
} from "../infrastructure/policy-config-repository.js";
import { InquirerSetupPrompter } from "../infrastructure/setup-prompter.js";

const program = new Command();

program
  .name("jira-task")
  .description("RULE.md 기반 Jira 업무 생성 및 검증 CLI")
  .version("0.1.1");

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

program
  .command("create")
  .description("검증한 JSON 이슈의 Jira 생성 요청을 만듭니다")
  .requiredOption("-f, --file <path>", "생성할 JSON 파일 경로")
  .option("-p, --project <key>", "기본 Jira 프로젝트 키를 일시적으로 덮어씁니다")
  .option("--dry-run", "Jira에 생성하지 않고 요청 초안만 JSON으로 출력합니다")
  .option("-c, --config <path>", "기본 정책과 Jira 프로젝트 키를 읽을 설정 파일")
  .action(async (options: { file: string; project: string; dryRun?: boolean; config?: string }) => {
    if (!options.dryRun) {
      console.error("생성 실패: 실제 Jira 생성은 아직 지원하지 않습니다. --dry-run을 지정하세요.");
      process.exitCode = 1;
      return;
    }

    try {
      const rawInput = await readJsonFile(options.file);
      const config = await loadJiraTaskConfig(options.config);
      const projectKey = options.project
        ? resolveJiraTaskConfig({ projectKey: options.project }).projectKey
        : config.projectKey;

      if (!projectKey) {
        throw new PolicyConfigurationError(
          "Jira 프로젝트 키가 없습니다. config set --project <key>로 기본값을 설정하거나 --project <key>를 지정하세요.",
        );
      }

      const result = createDraft(rawInput, projectKey, config.policy);

      printMessages("오류", result.errors, console.error);
      printMessages("경고", result.warnings, console.warn);

      if (!result.draft) {
        console.error(`\n생성 요청 초안을 만들지 못했습니다 (${result.errors.length}개 오류)`);
        process.exitCode = 1;
        return;
      }

      console.log(JSON.stringify(result.draft, null, 2));
    } catch (error) {
      const message =
        error instanceof InputFileError || error instanceof PolicyConfigurationError
          ? error.message
          : "입력 파일을 처리하지 못했습니다.";
      console.error(`생성 실패: ${message}`);
      process.exitCode = 1;
    }
  });

const configCommand = program.command("config").description("업무 정책 설정을 관리합니다");

configCommand
  .command("init")
  .description("편집 가능한 기본 설정 파일을 만듭니다")
  .option("-c, --config <path>", "만들 설정 파일 경로")
  .action(async (options: { config?: string }) => {
    try {
      const path = await initializePolicyConfig(options.config);
      console.log(`설정 파일을 만들었습니다: ${path}`);
    } catch (error) {
      const message = error instanceof PolicyConfigurationError ? error.message : "정책 설정 파일을 만들지 못했습니다.";
      console.error(`설정 실패: ${message}`);
      process.exitCode = 1;
    }
  });

configCommand
  .command("show")
  .description("현재 적용되는 업무 정책과 Jira 프로젝트 키를 출력합니다")
  .option("-c, --config <path>", "읽을 설정 파일 경로")
  .action(async (options: { config?: string }) => {
    try {
      const config = await loadJiraTaskConfig(options.config);
      console.log(
        JSON.stringify(
          {
            ...config.policy,
            ...(config.projectKey ? { projectKey: config.projectKey } : {}),
            ...(config.jira ? { jira: redactJiraSettings(config.jira) } : {}),
          },
          null,
          2,
        ),
      );
    } catch (error) {
      const message = error instanceof PolicyConfigurationError ? error.message : "정책 설정을 읽지 못했습니다.";
      console.error(`설정 실패: ${message}`);
      process.exitCode = 1;
    }
  });

configCommand
  .command("setup")
  .description("Jira 연결과 프로젝트 기본값을 대화형으로 설정합니다")
  .option("-c, --config <path>", "저장할 설정 파일 경로")
  .action(async (options: { config?: string }) => {
    await startInitialSetup(options.config);
  });

configCommand
  .command("set")
  .description("기본 Jira 프로젝트와 브랜치 생성용 Jira 연결 정보를 설정합니다")
  .option("-p, --project <key>", "저장할 Jira 프로젝트 키")
  .option("--base-url <url>", "Jira Cloud 주소")
  .option("--story-type <name>", "스토리 이슈 타입명")
  .option("--in-progress-status <name>", "브랜치 생성 후 적용할 진행 상태명")
  .option("-c, --config <path>", "저장할 설정 파일 경로")
  .action(
    async (options: {
      project?: string;
      baseUrl?: string;
      storyType?: string;
      inProgressStatus?: string;
      config?: string;
    }) => {
    try {
      if (!options.project && !options.baseUrl && !options.storyType && !options.inProgressStatus) {
        throw new PolicyConfigurationError("저장할 설정을 하나 이상 지정하세요.");
      }
      const path = await setJiraTaskConfig(
        {
          ...(options.project ? { projectKey: options.project } : {}),
          ...(options.baseUrl || options.storyType || options.inProgressStatus
            ? {
                jira: {
                  ...(options.baseUrl ? { baseUrl: options.baseUrl } : {}),
                  ...(options.storyType ? { storyIssueType: options.storyType } : {}),
                  ...(options.inProgressStatus ? { inProgressStatus: options.inProgressStatus } : {}),
                },
              }
            : {}),
        },
        options.config,
      );
      console.log(`Jira 기본 설정을 저장했습니다: ${path}`);
    } catch (error) {
      const message = error instanceof PolicyConfigurationError ? error.message : "정책 설정 파일을 저장하지 못했습니다.";
      console.error(`설정 실패: ${message}`);
      process.exitCode = 1;
    }
  });

const branchCommand = program.command("branch").description("스토리 기준 Git 브랜치를 관리합니다");

branchCommand
  .command("create")
  .description("스토리 번호로 브랜치를 만들고 Jira 스토리를 진행 중으로 전환합니다")
  .requiredOption("--story <number>", "현재 프로젝트의 Jira 스토리 번호")
  .requiredOption("--name <slug>", "소문자 영문·숫자·하이픈으로 된 짧은 작업명")
  .option("-c, --config <path>", "Jira 프로젝트와 연결 정보를 읽을 설정 파일")
  .action(async (options: { story: string; name: string; config?: string }) => {
    try {
      const config = await loadJiraTaskConfig(options.config);
      const branchConfig = await getBranchConfig(config, process.env, new KeyringCredentialStore());
      const result = await createBranch(
        {
          projectKey: branchConfig.projectKey,
          storyNumber: options.story,
          name: options.name,
          storyIssueType: branchConfig.storyIssueType,
          inProgressStatus: branchConfig.inProgressStatus,
        },
        new JiraClient({
          baseUrl: branchConfig.baseUrl,
          email: branchConfig.email,
          apiToken: branchConfig.apiToken,
        }),
        new GitRepository(),
      );

      if (result.kind === "success") {
        console.log(`브랜치를 만들었습니다: ${result.branchName}`);
        console.log(
          result.transitioned
            ? `Jira 스토리를 진행 중으로 전환했습니다: ${result.storyKey}`
            : `Jira 스토리는 이미 진행 중입니다: ${result.storyKey}`,
        );
        return;
      }
      if (result.kind === "partial-success") {
        console.error(`브랜치는 만들었지만 Jira 상태 전환에 실패했습니다: ${result.branchName} (${result.storyKey})`);
        console.error(result.message);
        process.exitCode = 4;
        return;
      }

      console.error(`브랜치 생성 실패: ${result.message}`);
      process.exitCode = result.exitCode;
    } catch (error) {
      const message = error instanceof PolicyConfigurationError || error instanceof CredentialStorageError ? error.message : "브랜치를 만들지 못했습니다.";
      console.error(`브랜치 생성 실패: ${message}`);
      process.exitCode = 2;
    }
  });

const taskCommand = program.command("task").description("현재 작업 중인 Jira 작업 번호를 관리합니다");

taskCommand
  .command("start")
  .description("커밋에 사용할 현재 작업 번호를 저장합니다")
  .requiredOption("--number <number>", "현재 프로젝트의 Jira 작업 번호")
  .action(async (options: { number: string }) => {
    try {
      const result = await startTask(options.number, new GitRepository());

      if (result.kind === "success") {
        console.log(`현재 작업 번호를 저장했습니다: ${result.taskNumber}`);
        return;
      }

      console.error(`작업 시작 실패: ${result.message}`);
      process.exitCode = result.exitCode;
    } catch {
      console.error("작업 시작 실패: 작업 번호를 저장하지 못했습니다.");
      process.exitCode = 2;
    }
  });

program
  .command("commit")
  .description("현재 작업 번호로 Jira 이슈 키를 붙여 스테이지된 변경 사항을 커밋합니다")
  .requiredOption("-m, --message <text>", "커밋 메시지")
  .option("-c, --config <path>", "Jira 프로젝트 키를 읽을 설정 파일")
  .action(async (options: { message: string; config?: string }) => {
    try {
      const config = await loadJiraTaskConfig(options.config);
      if (!config.projectKey) {
        throw new PolicyConfigurationError("Jira 프로젝트 키가 없습니다. config set --project <key>로 기본값을 설정하세요.");
      }

      const result = await createCommit({ projectKey: config.projectKey, message: options.message }, new GitRepository());

      if (result.kind === "success") {
        console.log(`커밋했습니다: ${result.commitMessage}`);
        return;
      }

      console.error(`커밋 실패: ${result.message}`);
      process.exitCode = result.exitCode;
    } catch (error) {
      const message = error instanceof PolicyConfigurationError ? error.message : "커밋하지 못했습니다.";
      console.error(`커밋 실패: ${message}`);
      process.exitCode = 2;
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

if (process.argv.slice(2).length === 0 && !(await hasJiraTaskConfig())) {
  if (!process.stdin.isTTY || !process.stdout.isTTY) {
    console.error("Jira 초기 설정이 필요합니다. 터미널에서 jira-task config setup을 실행하세요.");
    process.exitCode = 2;
  } else {
    await startInitialSetup();
  }
} else {
  await program.parseAsync();
}

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

async function getBranchConfig(
  config: Awaited<ReturnType<typeof loadJiraTaskConfig>>,
  environment: NodeJS.ProcessEnv,
  credentials: KeyringCredentialStore,
): Promise<{ projectKey: string; baseUrl: string; storyIssueType: string; inProgressStatus: string; email: string; apiToken: string }> {
  const storyIssueType = config.jira?.issueTypes?.story?.name ?? config.jira?.storyIssueType;
  const inProgressStatus = config.jira?.statuses?.story?.start?.name ?? config.jira?.inProgressStatus;
  const email = environment.JIRA_EMAIL ?? config.jira?.email;
  const baseUrl = config.jira?.baseUrl;
  const apiToken = environment.JIRA_API_TOKEN ?? (baseUrl && email ? await credentials.get(baseUrl, email) : undefined);
  const missing = [
    !config.projectKey ? "프로젝트 키(config set --project)" : undefined,
    !baseUrl ? "Jira 주소(config set --base-url)" : undefined,
    !storyIssueType ? "스토리 타입(config setup 또는 config set --story-type)" : undefined,
    !inProgressStatus ? "진행 상태(config setup 또는 config set --in-progress-status)" : undefined,
    !email ? "JIRA_EMAIL 또는 초기 설정 이메일" : undefined,
    !apiToken ? "JIRA_API_TOKEN 또는 운영체제 자격 증명 저장소의 토큰" : undefined,
  ].filter((value): value is string => Boolean(value));

  if (missing.length > 0) {
    throw new PolicyConfigurationError(`브랜치 생성 설정이 없습니다: ${missing.join(", ")}`);
  }

  return {
    projectKey: config.projectKey!,
    baseUrl: baseUrl!,
    storyIssueType: storyIssueType!,
    inProgressStatus: inProgressStatus!,
    email: email!,
    apiToken: apiToken!,
  };
}

async function startInitialSetup(configPath?: string): Promise<void> {
  if (!process.stdin.isTTY || !process.stdout.isTTY) {
    console.error("대화형 터미널에서 jira-task config setup을 실행하세요.");
    process.exitCode = 2;
    return;
  }

  try {
    console.log("jira-task 초기 설정을 시작합니다.\n");
    const result = await runInitialSetup({
      prompter: new InquirerSetupPrompter(),
      createJira: (connection) =>
        new JiraClient({ baseUrl: connection.baseUrl, email: connection.email, apiToken: connection.apiToken }),
      saveConfig: async (update) => {
        await setJiraTaskConfig(update, configPath);
      },
      credentials: new KeyringCredentialStore(),
    });
    console.log(`\n✓ jira-task 설정 완료 (${result.projectKey}, ${result.storyIssueType}, ${result.storyStartStatus})`);
  } catch (error) {
    if (error instanceof SetupCancelledError) {
      console.log("\n초기 설정을 취소했습니다.");
      return;
    }
    const message =
      error instanceof PolicyConfigurationError || error instanceof SetupSelectionError || error instanceof CredentialStorageError
        ? error.message
        : "Jira 초기 설정을 완료하지 못했습니다. Jira 주소, 인증 정보, 프로젝트 권한을 확인하세요.";
    console.error(`설정 실패: ${message}`);
    process.exitCode = 2;
  }
}

function redactJiraSettings(jira: NonNullable<Awaited<ReturnType<typeof loadJiraTaskConfig>>["jira"]>) {
  const { email: _email, ...safeSettings } = jira;
  return safeSettings;
}
