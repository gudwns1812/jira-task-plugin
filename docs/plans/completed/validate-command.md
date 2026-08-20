# `validate` CLI 명령 구현 계획

> 상태: 완료. JSON 파일의 구조와 RULE.md 업무 규칙을 검증하며, Jira에는 변경을 만들지 않는다.

## 목표

사용자가 작성한 JSON 파일을 Jira에 보내기 전에 검증한다.

```text
jira-task validate --file task.json
```

명령은 이슈를 생성하거나 파일을 수정하지 않는다. 오류·경고·자동 계산된 레이블만 출력하고, 오류가 있으면 종료 코드 `1`을 반환한다.

## 검증 순서

```text
JSON 파일 읽기
  → JSON 문법 검사
  → 입력 스키마 검사
  → RULE.md 업무 규칙 검사
  → 결과 출력
```

- JSON 문법 오류: 파일과 줄/문법 오류를 안내한다.
- 입력 스키마 오류: 필수값, 날짜, 포인트, 알 수 없는 필드를 알려준다.
- 업무 규칙 오류: 제목 형식, 업무 분류, 담당 영역, 결과물 규칙을 알려준다.
- 경고만 있으면 성공으로 처리하되, 사용자가 확인할 수 있도록 출력한다.

## 만들 파일

```text
src/
├─ application/
│  └─ validate-issue.ts          # 스키마와 업무 검증기를 하나로 조합
├─ infrastructure/
│  └─ file-input.ts              # UTF-8 JSON 파일 읽기와 JSON 문법 오류 변환
└─ cli/
   └─ index.ts                   # validate --file 명령 등록 및 결과 출력

tests/
├─ application/validate-issue.test.ts
├─ infrastructure/file-input.test.ts
└─ fixtures/
   ├─ valid-task.json
   └─ invalid-task.json
```

## 완료 기준

- 정상 작업 JSON은 `검증 성공`과 기본 레이블을 출력하고 종료 코드 `0`을 반환한다.
- JSON 문법 오류, 없는 파일, 누락 필드, 잘못된 제목은 사람이 고칠 수 있는 오류 메시지와 종료 코드 `1`을 반환한다.
- 경고는 오류와 분리해 출력한다.
- 단위 테스트, 타입 검사, 빌드, 실제 빌드된 CLI의 정상·오류 입력 실행이 모두 통과한다.
