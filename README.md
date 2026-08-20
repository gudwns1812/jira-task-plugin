# task-jira-cli

`RULE.md`에 정의한 Jira 업무 규칙을 검증하고, Jira 요청 초안을 만들며, Story 기준 Git 브랜치를 안전하게 생성하는 TypeScript CLI입니다.

이 도구는 업무 설명을 명령행 옵션으로 길게 입력하지 않습니다. Epic·Story·Task 내용을 JSON 파일로 작성하고, CLI는 검증·초안 생성·브랜치 생성만 담당합니다.

## 할 수 있는 일

- Epic, Story, Task 입력 JSON의 형식과 업무 규칙 검증
- Story·Task 설명 템플릿 출력
- Jira에 보낼 이슈 생성 요청 초안 출력 (`create --dry-run`)
- Jira Story 확인 후 `feat/<스토리 번호>-<이름>` Git 브랜치 생성
- 브랜치 생성 뒤 연결된 Jira Story를 설정한 진행 상태로 전환
- 사용자 정책(영역, 업무 분류, 포인트, 레이블) 설정

## 아직 하지 않는 일

- 실제 Jira Epic·Story·Task 생성
- 스프린트·포인트·Epic/Story 관계의 Jira 커스텀 필드 매핑
- CI/CD 파이프라인 구성

`create`는 현재 반드시 `--dry-run`을 사용해야 하며 Jira 이슈를 만들지 않습니다.

## 요구 사항

- Node.js 22 이상
- Git (브랜치 생성 기능 사용 시)
- Jira Cloud와 API 토큰 (Jira Story 확인·상태 전환 기능 사용 시)

## 설치

```bash
git clone https://github.com/gudwns1812/task-jira-cli.git
cd task-jira-cli
npm ci
npm run build
```

전역 명령으로 사용하려면 다음을 한 번 실행합니다.

```bash
npm link
jira-task --help
```

전역 설치 없이 개발 중 실행하려면 `jira-task` 대신 아래 형식을 사용합니다.

```bash
npm run dev -- --help
```

## 빠른 시작

### 1. 기본 정책 파일 만들기

```bash
jira-task config init
jira-task config show
```

기본 파일은 `~/.jira-task/policy.json`입니다. 팀에서 공유하는 설정 파일을 쓰려면 모든 명령에 `--config ./team-policy.json`을 지정합니다.

### 2. 프로젝트와 Jira 브랜치 연동 설정

`branch create`를 사용하려면 프로젝트 키와 Jira Cloud 정보를 설정합니다.

```bash
jira-task config set \
  --project S15P11A108 \
  --base-url https://team.atlassian.net \
  --story-type Story \
  --in-progress-status "In Progress"
```

`--story-type`, `--in-progress-status`에는 Jira 프로젝트에서 실제로 보이는 이름을 정확히 입력해야 합니다. 한국어 워크플로라면 예를 들어 `스토리`, `진행 중`일 수 있습니다.

API 자격 증명은 설정 파일이 아니라 환경 변수에 둡니다.

```bash
export JIRA_EMAIL="user@example.com"
export JIRA_API_TOKEN="your-api-token"
```

`JIRA_API_TOKEN`은 Atlassian API token이며, 명령 출력과 설정 파일에 저장되지 않습니다.

## JSON 입력 작성

긴 설명은 JSON으로 작성합니다. 먼저 템플릿을 확인할 수 있습니다.

```bash
jira-task template story
jira-task template task
```

### Task 예시

`task.json`

```json
{
  "type": "task",
  "epicKey": "S15P11A108-10",
  "storyKey": "S15P11A108-100",
  "title": "[AUTH-001 | FE] 이메일 회원가입 화면 구현",
  "assignee": "user@example.com",
  "priority": "High",
  "storyPoints": 3,
  "sprint": "Sprint 1",
  "dueDate": "2026-08-28",
  "description": {
    "summary": "회원가입 화면과 입력 상태를 구현한다.",
    "deliverables": [
      "입력 상태와 오류 메시지가 표시된다."
    ]
  }
}
```

### Story 예시

`story.json`

```json
{
  "type": "story",
  "epicKey": "S15P11A108-10",
  "title": "[AUTH-001] 이메일 회원가입",
  "branch": "feat/100-signup",
  "sprint": "Sprint 1"
}
```

### Epic 예시

`epic.json`

```json
{
  "type": "epic",
  "title": "[AUTH] 인증"
}
```

## 명령어 사용법

### `validate`: 입력 검증

```bash
jira-task validate --file task.json
```

사용자 정책 파일을 적용하려면 다음과 같이 실행합니다.

```bash
jira-task validate --file task.json --config ./team-policy.json
```

검증은 제목 형식, 이슈 타입별 필수값, 설명/결과물, 포인트, 허용 영역과 업무 분류를 확인합니다. 오류가 있으면 종료 코드 `1`을 반환합니다.

### `create --dry-run`: Jira 요청 초안 확인

```bash
jira-task create --file task.json --dry-run
```

프로젝트 키를 아직 저장하지 않았다면 한 번만 지정할 수 있습니다.

```bash
jira-task create \
  --file task.json \
  --project S15P11A108 \
  --dry-run
```

출력에는 프로젝트 키, 이슈 타입, 제목, Markdown 설명, 레이블, 우선순위, 기한, 스프린트·포인트 값, 관계 키가 포함됩니다. 이 명령은 네트워크를 호출하거나 Jira 이슈를 만들지 않습니다.

### `branch create`: 구현 브랜치 생성과 Story 진행 상태 전환

```bash
jira-task branch create --story 100 --name signup
```

위 명령은 설정된 프로젝트 키 `S15P11A108`과 번호 `100`을 합쳐 `S15P11A108-100`을 조회한 뒤 다음 순서로 동작합니다.

1. Jira 이슈가 설정된 Story 타입인지, 현재 상태가 무엇인지 확인
2. 현재 디렉터리가 Git 저장소인지, 작업 트리가 깨끗한지, 같은 이름의 로컬·원격 브랜치가 없는지 확인
3. `feat/100-signup` 브랜치를 만들고 전환
4. 이미 진행 중이었던 Story라면 전환 호출 없이 완료
5. 아니라면 Jira가 제공한 전환 목록에서 설정된 진행 상태로 가는 전환을 찾아 적용

브랜치는 다음 상황에서는 만들지 않습니다.

- Story 번호 또는 브랜치 이름 형식이 잘못됨
- Jira 이슈를 찾지 못했거나 Story 타입이 아님
- Git 저장소가 아님, 작업 트리가 더러움, 같은 브랜치가 이미 존재함
- Jira 설정 또는 `JIRA_EMAIL`/`JIRA_API_TOKEN`이 없음

브랜치가 만들어진 뒤 Jira 상태 전환만 실패하면 브랜치는 보존됩니다. 이 경우 종료 코드 `4`와 함께 생성된 브랜치명·Story 키를 출력하므로, Jira에서 상태를 직접 전환한 뒤 작업을 계속하면 됩니다.

### `config`: 정책과 Jira 연결 설정

```bash
# 기본 파일 생성
jira-task config init

# 현재 적용 설정 확인
jira-task config show

# 값 일부만 변경
jira-task config set --project S15P11A108
jira-task config set --in-progress-status "In Progress"
```

정책 파일에서는 허용 영역, 업무 분류, 포인트와 레이블도 조정할 수 있습니다.

```json
{
  "areas": ["FE", "BE", "AI", "QA", "INFRA"],
  "workCategories": ["PLAN", "UX", "DESIGN", "TECH", "INFRA", "DOCS", "QA", "PT"],
  "storyPoints": [1, 2, 3, 5, 8],
  "labels": {
    "areas": { "INFRA": "Infra" }
  }
}
```

## 종료 코드

`branch create`는 원인을 구분해 아래 종료 코드를 사용합니다.

| 코드 | 의미 |
| --- | --- |
| `0` | 브랜치 생성과 Jira 상태 전환 성공 |
| `1` | 입력 형식, Jira 이슈 없음, Story 타입 불일치 등 업무 규칙 오류 |
| `2` | 프로젝트/Jira 설정 또는 환경 변수 누락 |
| `3` | Jira 인증 또는 권한 오류 (브랜치 생성 전) |
| `4` | Jira 네트워크·서버 오류 또는 브랜치 생성 후 Jira 전환 실패 |
| `5` | Git 저장소·작업 트리·브랜치 상태 오류 |

다른 명령(`validate`, `create`, `config`, `template`)은 일반 오류에 종료 코드 `1`을 사용합니다.

## 개발과 검증

```bash
npm test
npm run typecheck
npm run build
```

테스트는 Jira API 대신 로컬 HTTP 서버를 사용하고, 브랜치 생성은 임시 Git 저장소에서 검증합니다. 따라서 테스트를 실행해도 실제 Jira 이슈·상태·브랜치를 변경하지 않습니다.

## 도움말

```bash
jira-task --help
jira-task config --help
jira-task create --help
jira-task branch create --help
```
