import { AsyncEntry } from "@napi-rs/keyring";

import type { CredentialStore } from "../application/run-initial-setup.js";

const SERVICE_NAME = "task-jira-cli";

export class CredentialStorageError extends Error {
  constructor() {
    super("운영체제 자격 증명 저장소에 Jira API 토큰을 저장하거나 읽지 못했습니다.");
    this.name = "CredentialStorageError";
  }
}

/** API 토큰은 설정 JSON과 분리해 운영체제 자격 증명 저장소에 보관한다. */
export class KeyringCredentialStore implements CredentialStore {
  async save(baseUrl: string, email: string, apiToken: string): Promise<void> {
    try {
      await new AsyncEntry(SERVICE_NAME, accountName(baseUrl, email)).setPassword(apiToken);
    } catch {
      throw new CredentialStorageError();
    }
  }

  async get(baseUrl: string, email: string): Promise<string | undefined> {
    try {
      return await new AsyncEntry(SERVICE_NAME, accountName(baseUrl, email)).getPassword();
    } catch {
      throw new CredentialStorageError();
    }
  }
}

function accountName(baseUrl: string, email: string): string {
  return `${baseUrl.replace(/\/$/, "").toLowerCase()}|${email.trim().toLowerCase()}`;
}
