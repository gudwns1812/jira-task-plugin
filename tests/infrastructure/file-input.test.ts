import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vitest";

import { InputFileError, readJsonFile } from "../../src/infrastructure/file-input.js";

const fixturePath = (name: string): string => fileURLToPath(new URL(`../fixtures/${name}`, import.meta.url));

describe("readJsonFile", () => {
  it("UTF-8 JSON 파일을 객체로 읽는다", async () => {
    await expect(readJsonFile(fixturePath("valid-task.json"))).resolves.toMatchObject({
      type: "task",
      storyPoints: 3,
    });
  });

  it("없는 파일과 잘못된 JSON을 구분해 거부한다", async () => {
    await expect(readJsonFile(fixturePath("missing.json"))).rejects.toThrow(InputFileError);
    await expect(readJsonFile(fixturePath("invalid-json.json"))).rejects.toThrow(
      "유효한 JSON 파일이 아닙니다",
    );
  });
});
