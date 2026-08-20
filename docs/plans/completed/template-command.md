# `template` CLI 명령 구현 계획

> 상태: 완료. 스토리·작업 설명 양식을 표준 출력으로 제공하며 외부 상태를 변경하지 않는다.

## 목표

`RULE.md`에 정의된 이슈 설명 양식을 사람이 복사해 바로 쓸 수 있게 출력한다.

```text
jira-task template story
jira-task template task
```

이 명령은 파일·Jira·Git 상태를 바꾸지 않는다. 표준 출력에 템플릿만 출력한다.

## 출력 내용

### 스토리

```text
예: (S15P11A108-100) [AUTH-001] 회원가입

`feat/100-signup`
```

이슈가 만들어진 뒤 생성한 브랜치 이름을 설명에 기록한다는 규칙을 안내한다.

### 작업

```text
## 설명
작업 설명을 작성합니다.

## 결과물
[] 완료 기준 결과물 1
[] 완료 기준 결과물 2
```

## 구현 구조

```text
src/
├─ domain/template/
│  └─ issue-template.ts       # 스토리·작업 템플릿 문자열의 단일 원본
└─ cli/index.ts               # template <story|task> 명령 등록 및 출력

tests/
├─ domain/template/issue-template.test.ts
└─ cli/template-command.test.ts
```

템플릿 문자열은 CLI 코드에 직접 넣지 않는다. 나중에 사용자 설정으로 템플릿을 바꾸거나 `create` 명령에서 재사용하기 쉽도록 도메인 모듈에 둔다.

## 오류 처리와 테스트

- 지원하지 않는 유형(예: `template epic`)은 Commander가 사용법과 오류를 출력하고 종료 코드 `1`을 반환한다.
- 스토리·작업 템플릿 각각이 정확한 섹션과 자리표시자를 포함하는지 단위 테스트한다.
- 빌드된 실제 CLI에서 정상 유형과 잘못된 유형을 실행해 출력·종료 코드를 확인한다.

## 완료 기준

- `template story`와 `template task`가 각각 `RULE.md`의 구조를 출력한다.
- 템플릿은 한 파일에서 관리된다.
- 정상·오류 테스트, 타입 검사, 빌드, 실제 CLI 실행이 모두 통과한다.
