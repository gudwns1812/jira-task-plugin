import { describe, expect, it } from "vitest";

import { createTaskKey, TaskNumberError, validateTaskNumber } from "../../../src/domain/task/task-number.js";

describe("task number", () => {
  it("프로젝트 키와 작업 번호로 Jira 이슈 키를 만든다", () => {
    expect(createTaskKey("S15P11A108", "105")).toBe("S15P11A108-105");
  });

  it("유효한 작업 번호는 통과시킨다", () => {
    expect(() => validateTaskNumber("1")).not.toThrow();
  });

  it.each(["0", "-1", "105a", " 105", ""])("잘못된 작업 번호 %s를 거부한다", (taskNumber) => {
    expect(() => validateTaskNumber(taskNumber)).toThrow(TaskNumberError);
    expect(() => createTaskKey("S15P11A108", taskNumber)).toThrow(TaskNumberError);
  });
});
