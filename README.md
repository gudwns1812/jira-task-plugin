# task-jira-cli

Jira 업무 입력을 검증하고, 요청 초안을 확인하며, Story 작업 브랜치를 만드는 명령행 도구입니다.

## 빠른 시작

```bash
npm install -g task-jira-cli

jira-task --help
```

Node.js 22 이상과 Git이 필요합니다.

## 처음 한 번 설정하기

### 1. 정책 파일 만들기

```bash
jira-task config init
jira-task config show
```

기본 설정 파일은 `~/.jira-task/policy.json`입니다. 팀 설정 파일을 따로 쓰려면 명령에 `--config ./team-policy.json`을 추가하면 됩니다.

### 2. Jira와 브랜치 생성 설정하기

Story 브랜치를 만들려면 프로젝트와 Jira 워크플로 이름을 설정합니다.

```bash
jira-task config set \
  --project S15P11A108 \
  --base-url https://team.atlassian.net \
  --story-type Story \
  --in-progress-status "In Progress"
```

`--story-type`과 `--in-progress-status`에는 Jira 화면에 표시되는 이름을 그대로 입력하세요. 팀 Jira가 한국어라면 `스토리`, `진행 중`처럼 설정할 수 있습니다.

Jira 인증 정보는 환경 변수로 설정합니다.

```bash
export JIRA_EMAIL="user@example.com"
export JIRA_API_TOKEN="your-api-token"
```

## 기본 사용 흐름

```text
템플릿 확인 → JSON 작성 → 검증 → Jira 요청 초안 확인 → Story 브랜치 생성
```

### 1. 템플릿 확인

```bash
jira-task template story
jira-task template task
```

### 2. JSON 작성

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

`epic.json`

```json
{
  "type": "epic",
  "title": "[AUTH] 인증"
}
```

### 3. 규칙 검증

```bash
jira-task validate --file task.json
```

팀 정책 파일을 적용하려면 다음처럼 실행합니다.

```bash
jira-task validate --file task.json --config ./team-policy.json
```

제목 형식, 이슈 타입별 필수값, 설명·결과물, 스토리 포인트, 영역과 업무 분류를 확인합니다.

### 4. Jira 요청 초안 확인

```bash
jira-task create --file task.json --dry-run
```

프로젝트 키를 일회성으로 지정할 수도 있습니다.

```bash
jira-task create --file task.json --project S15P11A108 --dry-run
```

출력 JSON에서 제목, 설명, 레이블, 우선순위, 기한, 포인트, 스프린트, 관계 키를 확인하세요.

### 5. Story 브랜치 만들기

```bash
jira-task branch create --story 100 --name signup
```

프로젝트 키가 `S15P11A108`이면 이 명령은 `S15P11A108-100` Story를 확인하고 `feat/100-signup` 브랜치를 만듭니다. 브랜치를 만든 뒤 Story를 설정한 진행 상태로 전환합니다.

다음 상태에서는 브랜치를 만들지 않습니다.

- Story 번호 또는 브랜치 이름 형식이 잘못된 경우
- Jira 이슈를 찾지 못했거나 Story 타입이 아닌 경우
- 현재 디렉터리가 Git 저장소가 아닌 경우
- 커밋하지 않은 변경 사항이 있는 경우
- 같은 이름의 로컬 또는 원격 브랜치가 이미 있는 경우
- Jira 설정 또는 환경 변수가 빠진 경우

브랜치 생성 후 Jira 상태 전환에 실패하면 브랜치는 남아 있습니다. 출력된 브랜치명과 Story 키를 확인한 뒤 Jira에서 상태를 전환하면 됩니다.

## 설정 명령

```bash
# 기본 파일 생성
jira-task config init

# 현재 설정 보기
jira-task config show

# 프로젝트 키만 변경
jira-task config set --project S15P11A108

# 진행 상태 이름만 변경
jira-task config set --in-progress-status "In Progress"
```

정책 파일에서 허용 영역, 업무 분류, 포인트, 레이블도 조정할 수 있습니다.

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

| 코드 | 의미 |
| --- | --- |
| `0` | 명령 성공 |
| `1` | 입력값 또는 업무 규칙 오류 |
| `2` | Jira 설정 또는 환경 변수 누락 |
| `3` | Jira 인증·권한 오류 |
| `4` | Jira 통신 오류 또는 브랜치 생성 후 상태 전환 실패 |
| `5` | Git 저장소·작업 트리·브랜치 상태 오류 |

## 도움말

```bash
jira-task --help
jira-task config --help
jira-task validate --help
jira-task create --help
jira-task branch create --help
```
