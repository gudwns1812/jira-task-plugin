# `create --dry-run` 구현 계획

> 상태: 완료. 입력 JSON을 검증한 뒤 Jira에 보낼 생성 요청 초안을 출력하며, Jira에는 변경을 만들지 않는다.

## 다음에 구현할 기능

입력 JSON을 검증한 뒤 Jira에 보낼 생성 요청 초안을 출력한다.

```text
jira-task create --file task.json --project S15P11A108 --dry-run
```

`--dry-run`은 네트워크를 호출하거나 Jira 이슈를 만들지 않는다. 사용자는 실제 생성 전 제목·설명·레이블·기한·포인트·연결 정보를 확인할 수 있다.

## 동작 흐름

```text
JSON 파일 읽기
  → 사용자 정책 반영
  → 입력 스키마·업무 규칙 검증
  → Jira 생성 요청 초안 조립
  → JSON 출력
```

오류가 있으면 초안을 출력하지 않고 종료 코드 `1`을 반환한다.

## 출력 예시

```json
{
  "projectKey": "S15P11A108",
  "issueType": "task",
  "summary": "[AUTH-001 | FE] 이메일 회원가입 화면 구현",
  "description": "## 설명\n...\n\n## 결과물\n[] ...",
  "labels": ["FE"],
  "priority": "High",
  "dueDate": "2026-08-28",
  "policyFields": {
    "storyPoints": 3,
    "sprint": "Sprint 1"
  },
  "relationships": {
    "epicKey": "S15P11A108-10",
    "storyKey": "S15P11A108-100"
  }
}
```

`policyFields`와 `relationships`는 나중에 Jira 사이트별 커스텀 필드 ID·링크 타입으로 변환한다. 지금은 값의 손실 없이 초안에 보관한다.

## 만들 파일

```text
src/
├─ application/create-draft.ts       # 검증 성공 시 생성 요청 초안 조립
├─ domain/jira/create-request.ts     # Jira에 독립적인 요청 초안 타입·설명 포맷터
└─ cli/index.ts                      # create --file --project --dry-run 명령

tests/
├─ application/create-draft.test.ts
└─ domain/jira/create-request.test.ts
```

## 완료 기준

- 정상 작업·스토리·에픽 입력이 각각 초안으로 변환된다.
- 작업 설명은 `RULE.md`의 `## 설명`·`## 결과물` 형식으로 만들어진다.
- 잘못된 입력은 Jira 요청 초안을 만들지 않는다.
- `--dry-run`이 없으면 실제 생성 대신 안전하게 사용법 오류를 반환한다.
- 단위 테스트, 타입 검사, 빌드, 실제 CLI의 정상·오류 실행이 모두 통과한다.
