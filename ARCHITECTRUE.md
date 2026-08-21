# task-jira-cli 아키텍처

> 이 문서는 `RULE.md`의 Jira 운영 규칙을 강제하는 CLI 프로그램의 구현 설계서다.
> 처음 만드는 CLI라면, 이 문서의 **구현 순서**대로 작은 기능부터 완성한다.

## 1. 목표와 범위

### 목표

사용자가 명령어로 에픽·스토리·작업을 만들 때 다음을 자동으로 확인하고 처리한다.

- 제목, 이슈 유형, 필수 입력값, 설명 형식이 `RULE.md`를 만족하는지 검증한다.
- 유형별 기본 레이블과 설명 템플릿을 자동 적용한다.
- 작업의 스토리 포인트와 담당 영역의 일관성을 검증한다.
- Jira 이슈 입력을 검증하고 요청 초안을 만든다. 실제 이슈 생성과 관계 연결은 현재 범위에서 제외한다.

### 초기 버전(MVP) 범위

- `validate`: 입력 파일 또는 옵션을 규칙에 맞게 검증
- `create --dry-run`: 검증을 통과한 한 개의 이슈 요청 초안 생성
- `branch create`: 스토리 이슈 번호를 포함한 Git 브랜치 생성
- `template`: 스토리/작업 설명 템플릿 출력
- `config`: Jira 연결 정보와 프로젝트 기본값 관리

처음부터 스프린트 생성, 일괄 수정은 넣지 않는다. Jira 연결 설정은 첫 실행 대화형 마법사로 제공하며, 브랜치는 스토리의 핵심 산출물이므로 MVP에 포함한다.

### 비범위

- 서브 작업 생성: 규칙상 지원하지 않는다.
- 에픽·스토리에 스토리 포인트 입력: 규칙상 지원하지 않는다.
- Jira 외 프로젝트 관리 도구 연동

## 2. 추천 기술 선택

빈 프로젝트이므로 아래 조합을 권장한다. Node.js와 TypeScript는 CLI 예제와 라이브러리가 많고, 타입으로 Jira 입력값 실수를 일찍 막을 수 있다.

| 역할 | 추천 | 이유 |
| --- | --- | --- |
| 런타임 | Node.js LTS | Jira REST API 호출과 CLI 생태계가 성숙함 |
| 언어 | TypeScript | 이슈 유형별 필수 필드를 타입으로 표현 가능 |
| 명령 파싱 | `commander` | 선언형 명령/옵션 정의가 단순함 |
| 입력 검증 | `zod` | 옵션·JSON 입력과 도메인 규칙을 단계적으로 검증 |
| 대화형 입력 | `@inquirer/prompts` | 비밀번호 마스킹·선택 목록·확인 UX 제공 |
| 자격 증명 | OS credential store | API 토큰을 JSON 설정 파일과 분리 |
| HTTP | 내장 `fetch` | Jira REST API 호출에 충분함 |
| 테스트 | Vitest | 규칙 검증 함수를 빠르게 단위 테스트 |
| 배포 | npm package + `bin` | `jira-task`처럼 전역 명령으로 실행 |

## 3. 사용자 경험과 명령어

실행 파일 이름은 예시로 `jira-task`를 사용한다.

```text
jira-task
jira-task template task
jira-task validate --file task.json
jira-task create --file task.json --dry-run
jira-task branch create --story 100 --name signup
jira-task commit --message "이메일 회원가입 화면 구현"
```

### 명령별 책임

| 명령 | 책임 | Jira API 호출 |
| --- | --- | --- |
| `config setup` | Jira 연결 확인과 프로젝트·유형·상태 선택 | 읽기 전용 조회 |
| `config set/show` | 자동화용 로컬 설정 저장·조회 | 없음 |
| `template <story|task>` | 설명 작성용 템플릿 출력 | 없음 |
| `validate --file <path>` | 입력값과 규칙 검증, 오류 목록 출력 | 기본 없음 |
| `create --dry-run` | Jira 요청 본문을 출력하고 생성하지 않음 | 없음 |
| `branch create` | 스토리 번호와 작업명으로 Git 브랜치를 생성하고 스토리를 진행 중으로 전환 | Jira 조회·전환 |
| `commit` | 현재 브랜치명에서 읽은 스토리 번호로 이슈 키를 만들어 커밋 메시지에 붙이고 커밋 | 없음 |

`create --dry-run`은 내부에서 항상 `validate`와 동일한 검증기를 호출해야 한다. 검증 명령과 초안 명령의 규칙이 갈라지는 일을 막는다.

## 4. 입력 형식

처음에는 옵션을 많이 받기보다 JSON 파일을 공식 입력 형식으로 둔다. 복잡한 설명과 결과물을 셸 이스케이프 없이 작성할 수 있고, Git으로 리뷰하기도 쉽다. 자주 쓰는 단순 값만 CLI 옵션으로 덮어쓴다.

### 작업 입력 예시: `task.json`

```json
{
  "type": "task",
  "epicKey": "S15P11A108-10",
  "storyKey": "S15P11A108-100",
  "title": "[AUTH-001 | FE] 이메일 회원가입 화면 및 입력 상태 구현",
  "assignee": "user@example.com",
  "priority": "High",
  "storyPoints": 3,
  "sprint": "Sprint 1",
  "dueDate": "2026-08-28",
  "description": {
    "summary": "회원가입 화면과 이메일·비밀번호 입력 상태를 구현한다.",
    "deliverables": [
      "이메일·비밀번호 입력 및 오류 상태가 표시된다.",
      "디자인 시안과 일치하는 회원가입 화면이 구현된다."
    ]
  }
}
```

CLI는 이 JSON을 아래 형태로 변환한다.

```text
## 설명
회원가입 화면과 이메일·비밀번호 입력 상태를 구현한다.

## 결과물
[] 이메일·비밀번호 입력 및 오류 상태가 표시된다.
[] 디자인 시안과 일치하는 회원가입 화면이 구현된다.
```

### 스토리 입력 예시

```json
{
  "type": "story",
  "epicKey": "S15P11A108-10",
  "title": "[AUTH-001] 이메일 회원가입",
  "branch": "feat/100-signup",
  "sprint": "Sprint 1"
}
```

## 5. 디렉터리 구조

```text
src/
├─ cli/
│  ├─ index.ts                 # 실행 진입점: 명령 등록 및 종료 코드 결정
│  └─ commands/
│     ├─ create.ts             # create 명령의 입력 읽기와 출력만 담당
│     ├─ validate.ts
│     ├─ template.ts
│     ├─ config.ts
│     └─ branch.ts             # branch create 명령의 입력/출력 처리
├─ domain/
│  ├─ issue.ts                 # Epic, Story, Task 타입과 공통 인터페이스
│  ├─ rules.ts                 # RULE.md를 코드 규칙으로 표현한 상수/정규식
│  ├─ validator.ts             # 순수 검증 함수: IssueInput -> ValidationResult
│  ├─ formatter.ts             # 스토리/작업 설명 생성
│  └─ errors.ts                # 사용자에게 보여 줄 구조화된 오류 타입
├─ application/
│  ├─ validate-issue.ts        # 입력 파싱 후 domain validator 실행
│  ├─ create-issue.ts          # 검증 -> Jira 생성 -> 관계 연결의 유스케이스
│  └─ create-branch.ts         # 스토리 확인 -> 이름 생성 -> Git 브랜치 생성
├─ infrastructure/
│  ├─ jira-client.ts           # Jira REST API 어댑터
│  ├─ git-client.ts            # git 상태 조회와 브랜치 생성 어댑터
│  ├─ config-repository.ts     # 로컬 설정 파일 읽기/쓰기
│  └─ file-input.ts            # JSON 파일 읽기와 Zod 스키마 검증
└─ shared/
   └─ result.ts                # 성공/실패 결과 타입 등 작은 공통 코드
tests/
├─ domain/validator.test.ts    # 규칙별 단위 테스트
├─ application/create-issue.test.ts
└─ fixtures/                   # 정상/오류 입력 JSON
```

의존 방향은 한 방향으로만 유지한다.

```text
CLI(입출력) -> Application(흐름) -> Domain(규칙)
                       |
                       -> Infrastructure(Jira·파일 구현)
```

- `domain`은 `commander`, 파일 시스템, HTTP를 import하지 않는다. 테스트가 가장 쉬운 순수 코드로 둔다.
- `cli`는 규칙을 직접 판단하지 않는다. 옵션을 받아 유스케이스를 호출하고 결과를 출력한다.
- `infrastructure`만 Jira의 필드 ID, HTTP 형식, 인증 방식을 안다.

## 6. 핵심 도메인 모델

```ts
type IssueType = "epic" | "story" | "task";
type Area = "FE" | "BE" | "AI" | "QA" | "INFRA";
type WorkCategory = "PLAN" | "UX" | "DESIGN" | "TECH" | "INFRA" | "DOCS" | "QA" | "PT";

interface BaseIssueInput {
  type: IssueType;
  title: string;
  epicKey?: string;
}

interface StoryInput extends BaseIssueInput {
  type: "story";
  epicKey: string;
  branch: string;
  sprint?: string;
}

interface TaskInput extends BaseIssueInput {
  type: "task";
  epicKey: string;
  storyKey?: string;
  assignee: string;
  priority: string;
  storyPoints: 1 | 2 | 3 | 5 | 8;
  sprint: string;
  dueDate: string; // ISO 8601 날짜(YYYY-MM-DD)
  description: { summary: string; deliverables: string[] };
}
```

`TaskInput`의 `storyKey`는 기획·디자인 등 독립 작업에서는 선택값으로 둘 수 있다. 단, 기능 ID를 가진 구현 작업이면 스토리와 연결하는 것을 검증한다.

## 7. `RULE.md`를 코드로 옮기는 검증 규칙

아래 규칙은 `src/domain/validator.ts`에 작은 함수로 나눈다. 오류는 하나만 반환하지 말고 가능한 한 모두 모아서 보여 준다.

| 규칙 | 적용 대상 | 검증 방식 |
| --- | --- | --- |
| 서브 작업 금지 | 전체 | `type === "subtask"`를 입력 스키마에서 거부 |
| 에픽 제목 | epic | 기능 에픽은 `[CODE] 이름`, 지원 에픽은 명사형 제목 허용 |
| 스토리 제목 | story | `^\\[[A-Z]+-\\d{3}\\] .+` 정규식 |
| 기능 작업 제목 | task | `^\\[[A-Z]+-\\d{3} \\| (FE|BE|AI|QA|INFRA)\\] .+` 정규식 |
| 공통 작업 제목 | task | `[PLAN] ...` 또는 `[PLAN | FE] ...` 형식 및 공식 업무 분류 검사 |
| 작업 필수 항목 | task | epic, 담당자, 우선순위, 포인트, 스프린트, 기한 모두 존재 |
| 에픽/스토리 포인트 금지 | epic, story | `storyPoints`가 있으면 오류 |
| 작업 설명 | task | 설명과 결과물 목록이 비어 있지 않은지 검사 |
| 포인트 허용값 | task | `1, 2, 3, 5, 8`만 허용 |
| 레이블 | story, task | 스토리는 `feat`; 작업은 담당 영역에 맞는 하나의 기본 레이블 (`INFRA` 영역은 `Infra`) |
| 기능 연결 | 기능 작업 | 작업 제목의 기능 ID와 스토리 제목의 기능 ID 일치 확인 |

정규식만으로 “작업이 0.5~2일 규모인지”, “완료 기준이 객관적인지”를 확실히 판별할 수 없다. 이 둘은 자동 경고(`warning`)로 시작하고, 오류(`error`)로 막아야 하는 규칙과 구분한다.

## 8. Jira 연동 경계

Jira 프로젝트마다 이슈 타입 이름과 커스텀 필드 ID가 다를 수 있다. 따라서 코드에 `customfield_10016` 같은 값을 박아 넣지 말고 설정으로 분리한다.

```json
{
  "baseUrl": "https://team.atlassian.net",
  "projectKey": "S15P11A108",
  "issueTypes": { "epic": "에픽", "story": "스토리", "task": "작업" },
  "fields": {
    "epicLink": "customfield_XXXXX",
    "sprint": "customfield_YYYYY",
    "storyPoints": "customfield_ZZZZZ"
  },
  "linkTypes": { "storyToTask": "다음으로 분할" }
}
```

### 생성 흐름

```text
입력 JSON/옵션
  -> Zod 형식 검사
  -> 도메인 규칙 검사
  -> Jira 요청 본문 출력 후 종료
```

실제 이슈 생성과 관계 링크는 현재 범위 밖이다. 나중에 추가할 때는 사이트별 링크 타입과 `epicLink` 필드 ID를 테스트 프로젝트에서 먼저 확인한다.

## 9. Git 브랜치 생성

브랜치는 **스토리에서만** 생성한다. 에픽에서는 브랜치를 만들지 않으며, 작업은 생성된 스토리 브랜치에서 수행한다. 이는 `RULE.md`의 브랜치 운영 기준을 그대로 따른다.

### 브랜치 이름 규칙

```text
feat/<스토리 이슈 번호의 마지막 숫자>-<짧은 영문 작업명>

예: S15P11A108-100 + signup -> feat/100-signup
```

- `--story`에는 현재 설정된 프로젝트의 스토리 번호를 받는다. `100`은 설정된 프로젝트 키와 조립해 `S15P11A108-100`으로 조회한다.
- CLI는 Jira에서 해당 이슈를 조회해 실제 유형이 설정된 스토리 타입인지 확인한다. 스토리가 아니면 생성하지 않는다.
- `--name`은 소문자 영문·숫자·하이픈만 허용한다. 예: `signup`, `email-login`, `sso-auth`.
- 작업 디렉터리에 커밋되지 않은 변경 사항이 있거나 같은 이름의 로컬·원격 브랜치가 있으면 중단한다.
- 브랜치를 만든 뒤 Jira가 제공한 전환 목록에서 설정된 진행 상태로 향하는 전환을 찾아 적용한다. 이미 진행 중이면 전환 호출 없이 성공한다.
- 브랜치 생성 후 Jira 전환이 실패하면 생성한 브랜치는 보존하고 partial success로 알린다.

### 생성 흐름

```text
branch create --story 100 --name signup
  -> 설정된 프로젝트 키와 조립해 S15P11A108-100 결정
  -> Jira에서 이슈 조회 및 스토리 유형 확인
  -> 스토리 번호와 --name으로 브랜치명 결정
  -> Git 저장소인지 확인
  -> 작업 디렉터리 변경 사항·동명 브랜치 확인
  -> git switch -c feat/100-signup
  -> Jira 전환 목록 조회 후 진행 상태 전환
  -> 생성된 브랜치명과 스토리 상태 출력
```

`create story`는 Jira 이슈 키를 받은 뒤 `--create-branch` 옵션이 있을 때 위 유스케이스를 이어서 실행할 수 있다. 처음에는 별도 명령 `branch create`를 기본으로 구현해, Jira 이슈 생성과 Git 변경을 명시적으로 분리한다.

## 10. 설정 및 보안

- 설정 파일: 사용자 홈 디렉터리의 `~/.jira-task/policy.json`을 기본 경로로 사용한다.
- 최초 실행 `jira-task`는 설정 파일이 없을 때 대화형 마법사를 시작한다. `config setup`은 마법사를 다시 실행한다.
- 마법사는 인증을 확인한 뒤 접근 가능한 프로젝트, 이슈 유형, 이슈 유형별 상태를 조회해 선택받는다.
- 토큰은 `policy.json`에 저장하지 않고 운영체제 자격 증명 저장소에 보관한다. 환경 변수 `JIRA_EMAIL`, `JIRA_API_TOKEN`은 저장된 자격 증명보다 우선한다.
- 설정 파일에는 Jira 주소·이메일·프로젝트·이슈 유형·상태의 ID와 표시 이름만 저장한다. `config show`는 이메일을 출력하지 않는다.
- `config set`은 자동화용 하위 호환 명령으로 URL, 프로젝트 키, 스토리 타입명, 진행 상태명처럼 비밀이 아닌 값만 저장한다.
- 네트워크 오류와 인증 오류에는 토큰 원문이나 HTTP `Authorization` 헤더를 출력하지 않는다.

## 11. 오류 출력과 종료 코드

오류는 사용자가 바로 수정할 수 있도록 `경로: 이유: 기대 형식` 순으로 출력한다.

```text
검증 실패 (2개)
- title: 작업 제목의 담당 영역은 FE, BE, AI, QA, INFRA 중 하나여야 합니다.
  입력값: [AUTH-001 | Frontend] 회원가입 화면 구현
- dueDate: YYYY-MM-DD 형식의 기한이 필요합니다.
```

| 종료 코드 | 의미 |
| --- | --- |
| `0` | 성공 |
| `1` | 입력 형식 또는 업무 규칙 위반 |
| `2` | 설정 누락 |
| `3` | Jira 인증/권한 오류 |
| `4` | Jira 네트워크·서버 오류 또는 브랜치 생성 후 Jira 전환 실패(partial success) |
| `5` | Git 저장소·브랜치·작업 디렉터리 상태 오류 |

## 12. 구현 순서

1. `package.json`, TypeScript 설정, `jira-task --help`만 동작하는 진입점을 만든다.
2. `IssueInput` 타입과 JSON 입력 Zod 스키마를 만든다.
3. Jira 연결 없이 `domain/validator.ts`를 작성하고, 정상/오류 입력 fixture로 테스트한다.
4. `template`과 `validate` 명령을 완성한다. 이 시점부터 팀이 규칙 검증기에 사용할 수 있다.
5. `--dry-run`으로 Jira 요청 본문을 출력하는 `create`를 만든다.
6. `branch create`와 Git 어댑터를 추가한다. 정상 생성, 비-스토리 거부, 변경 사항 존재, 동명 브랜치 존재를 테스트한다.
7. Jira API 어댑터와 `config` 명령을 추가하고, 테스트 프로젝트에서 에픽·스토리·작업을 하나씩 생성한다.
8. 스토리-작업 연결과 레이블/스프린트/포인트 커스텀 필드 매핑을 붙인다.
9. CI에서 `npm test`, `npm run typecheck`, `npm run lint`를 실행한다.

각 단계가 끝날 때 실제 실행 예시와 테스트 하나 이상을 추가한다. API 연결보다 검증 로직을 먼저 완성하면, Jira 테스트 이슈를 불필요하게 만들지 않고 규칙을 안정화할 수 있다.

## 13. 최초 완료 기준

다음 조건을 모두 만족하면 첫 번째 배포 준비가 된 것으로 본다.

- 잘못된 제목, 서브 작업, 누락된 작업 필수값을 `validate`가 정확히 막는다.
- `template task`가 `RULE.md`의 작업 설명 구조를 출력한다.
- `create --dry-run` 결과에 이슈 타입, 제목, 설명, 레이블, 스프린트, 기한, 포인트가 포함된다.
- 스토리 키 `S15P11A108-100`와 이름 `signup`으로 `feat/100-signup` 브랜치가 생성되고 전환된다.
- 에픽·작업 키, 더러운 작업 디렉터리, 이미 존재하는 브랜치에서는 안전하게 브랜치 생성을 중단한다.
- 테스트 Jira 프로젝트에서 에픽 1개, 스토리 1개, 작업 1개가 올바른 관계와 필드로 생성된다.
- API 토큰이 소스 코드·설정 파일·로그에 남지 않는다.
