import { readFile } from "node:fs/promises";

export class InputFileError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "InputFileError";
  }
}

export async function readJsonFile(filePath: string): Promise<unknown> {
  let contents: string;

  try {
    contents = await readFile(filePath, "utf8");
  } catch {
    throw new InputFileError(`입력 파일을 읽을 수 없습니다: ${filePath}`);
  }

  try {
    return JSON.parse(contents) as unknown;
  } catch {
    throw new InputFileError(`유효한 JSON 파일이 아닙니다: ${filePath}`);
  }
}
