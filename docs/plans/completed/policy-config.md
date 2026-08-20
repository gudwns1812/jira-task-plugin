# 사용자 정책 설정 구현 계획

> 상태: 완료. 기본 `RULE.md` 정책은 유지되며, 정책 설정 파일 또는 `--config` 옵션으로 덮어쓸 수 있다.

## 다음에 구현할 기능

현재 `work-policy.ts`에는 `RULE.md` 기준의 기본 업무 정책이 들어 있다. 다음 구현에서는 사용자가 설정 파일에서 이 기본값을 덮어쓸 수 있게 한다.

이 기능은 사용자가 이슈를 만들 때마다 카테고리를 자유롭게 정하게 하는 것이 아니다. **프로젝트 또는 개인이 한 번 설정한 정책**을 모든 `validate`·향후 `create` 명령이 공통으로 사용하게 만드는 기능이다.

## 사용자 흐름

```text
jira-task config init
  → ~/.jira-task/policy.json에 기본 정책 파일 생성

jira-task config show
  → 현재 적용되는 기본값과 덮어쓴 값을 보여 줌

jira-task validate --file task.json
  → 설정 파일이 있으면 그 정책으로 검증
```

공유할 정책 파일을 쓰고 싶다면 `--config ./team-policy.json`처럼 경로를 명시한다. 기본 경로의 개인 설정 파일은 Git에 포함하지 않는다.

## 설정 파일 예시

```json
{
  "areas": ["FE", "BE", "AI", "QA", "INFRA"],
  "workCategories": ["PLAN", "UX", "DESIGN", "TECH", "INFRA", "DOCS", "QA", "PT"],
  "storyPoints": [1, 2, 3, 5, 8],
  "labels": {
    "areas": { "INFRA": "Infra" },
    "categories": { "PLAN": "기획", "UX": "기획", "DESIGN": "기획", "DOCS": "Docs" }
  }
}
```

파일에서 일부만 작성하면 나머지는 `RULE.md` 기준 기본값을 사용한다. 잘못된 설정(빈 목록, 중복 값, 8점 초과 포인트, 존재하지 않는 영역의 레이블)은 시작 시 명확한 오류로 알려 준다.

## 내부 구조

```text
기본 정책(work-policy.ts)
  + 사용자 설정(policy.json)
  → 정책 해석기(resolvePolicy)
  → validateIssue(input, policy)
```

검증기가 `work-policy.ts`를 직접 import하지 않고 `Policy` 객체를 받도록 바꾼다. 따라서 기본 동작은 그대로 유지하면서 특정 프로젝트만 다른 규칙을 적용할 수 있다.

## 이번 범위

- 기본 정책 객체와 사용자 덮어쓰기 설정의 Zod 스키마
- `config init`, `config show` 명령
- `validate --config <path>` 연동
- 설정 파일·정책 해석·잘못된 설정·기본 정책 호환성 테스트

Jira URL·API 토큰·프로젝트 키 설정은 다음 Jira 연동 단계에서 별도로 다룬다. 정책 설정 파일에는 민감한 값을 넣지 않는다.

## 완료 기준

- 설정 파일이 없으면 현재와 동일하게 `RULE.md` 기본 정책으로 검증한다.
- 설정 파일이 있으면 허용 영역·분류·포인트·레이블을 덮어쓴다.
- 잘못된 설정은 이슈 입력 오류와 구분해 알려 준다.
- `config init`이 편집 가능한 기본 설정 파일을 만든다.
- 단위 테스트, 타입 검사, 빌드, 실제 CLI 실행이 모두 통과한다.
