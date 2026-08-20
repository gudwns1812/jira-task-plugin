#!/usr/bin/env node

import { Command } from "commander";

const program = new Command();

program
  .name("jira-task")
  .description("RULE.md 기반 Jira 업무 생성 및 검증 CLI")
  .version("0.1.0");

program.parse();
