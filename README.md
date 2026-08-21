# task-jira-cli

Jira 업무 입력을 검증하고, 요청 초안을 확인하며, Story 작업 브랜치를 만드는 명령행 도구입니다.

## 빠른 시작

```bash
npm install -g task-jira-cli

jira-task --help
```

Node.js 22.13 이상과 Git이 필요합니다.

## 처음 한 번 설정하기

### 1. 초기 설정 마법사 실행

```bash
jira-task
```

Jira 주소·이메일·API 토큰을 입력하면 연결을 확인한 뒤, 접근 가능한 프로젝트와 실제 이슈 유형·상태를 선택합니다. API 토큰은 설정 파일이 아닌 운영체제 자격 증명 저장소에 보관됩니다.

중간에 취소하면 어떤 설정도 저장하지 않습니다. 설정을 다시 하려면 다음 명령을 사용하세요.

```bash
jira-task config setup
```

기본 설정 파일은 `~/.jira-task/policy.json`입니다. 팀 설정 파일을 따로 쓰려면 명령에 `--config ./team-policy.json`을 추가하면 됩니다.

자동화 환경에서는 마법사 대신 환경 변수를 사용할 수 있습니다. 환경 변수는 저장된 자격 증명보다 우선합니다.

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
# 설정 마법사 다시 실행
jira-task config setup

# 현재 설정 보기 (이메일·토큰은 출력하지 않음)
jira-task config show

# 자동화 스크립트에서 비밀이 아닌 설정 변경
jira-task config set --project S15P11A108
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
