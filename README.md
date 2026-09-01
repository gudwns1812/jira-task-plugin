# jira-task-plugin

IntelliJ IDEA의 Git 흐름에 Jira를 끼워 넣는 플러그인이다.

Jira를 대신 조작하지 않는다. 에픽·스토리·작업은 사람이 스프린트 전에 Jira에서 전부 만들고
([RULE.md](RULE.md) 전제 A), 상태는 대부분 Jira automation이 옮긴다. 플러그인이 하는 일은
그 계획을 **개발하는 동안 잊지 않게** 돕는 것이다 — 작업을 고르고, 커밋에 키를 붙이고,
커밋 전에 한 번 확인하고, MR을 만들고, 이슈를 찾아본다.

## 기능

| 기능 | 위치 |
| --- | --- |
| 초기 설정 마법사 | `Tools > Jira Task > Run Setup Wizard`, 설정이 없으면 시작 시 알림 |
| 설정 확인·수정 | `Settings \| Tools \| Jira Task` |
| 현재 스프린트의 내 작업 고르기 | `Tools > Jira Task > Start Jira Task`, 상태 표시줄 위젯(클릭하면 변경) |
| 커밋 메시지 이슈 키 자동 접두 | Git 커밋 창(별도 조작 없음), `Tools > Jira Task > Commit with Jira Task` |
| 커밋 전 확인 | 커밋 직전 자동. 키 누락·오타·스프린트 이탈을 경고하고 막지는 않는다 |
| GitLab MR 만들기 | `Tools > Jira Task > Create Merge Request` |
| 이슈 검색·간단 수정 | `Tools > Jira Task > Search Jira Issues` |
| 이슈 키 클릭 → Jira 열기 | 커밋 메시지·VCS 로그·주석 어디서나(설정을 자동 등록한다) |
| 현재 상태 한눈에 보기 | 오른쪽 사이드바 `Jira Task` 툴 윈도우 |

## 설정과 자격 증명

- 설정은 IntelliJ 표준 방식(애플리케이션 레벨 `PersistentStateComponent`, `jira-task.xml`)으로 보관한다.
- Jira API 토큰과 GitLab 액세스 토큰은 설정 파일에 저장하지 않고 IDE 비밀번호 저장소(PasswordSafe)에만
  보관한다. 이메일과 토큰은 설정 화면에 표시하지 않으며, 토큰 입력 칸은 저장된 값을 되읽어 채우지 않는다.
- `JIRA_EMAIL`·`JIRA_API_TOKEN` 환경 변수가 있으면 저장된 값보다 먼저 사용한다.
- GitLab 토큰은 반대로, **설정 화면에 저장한 값이 `GITLAB_TOKEN` 환경 변수보다 우선**한다.
  환경 변수는 화면에 저장한 토큰이 없을 때만 쓴다.
- 설정 화면의 **연결 테스트**로 GitLab 주소와 토큰이 통하는지 미리 확인할 수 있다.
  MR 생성에는 `api` 스코프와 프로젝트 Developer 이상 권한이 필요하다 — `read_api`로는 조회만 된다.
- **AI 초안은 API 키를 쓰지 않는다.** 설치해 로그인해 둔 Claude Code CLI를 그대로 부르므로
  Pro/Max 구독 요금제로 동작하고, 플러그인은 어떤 자격 증명도 보관하지 않는다. 기본값은 꺼짐이다.
- AI에게 보내는 것은 **브랜치 이름·커밋 제목·변경된 파일 경로**뿐이다. **파일 내용은 보내지 않는다.**
- 저장소에 MR 템플릿(`.gitlab/merge_request_templates`)이 있으면 확인 창에서 고를 수 있다.
  AI를 쓰든 안 쓰든 템플릿을 따르며, 템플릿 글자는 지우지 않고 관련 이슈 절만 채운다.
- 초기 설정은 IDE 시작 직후 모달 창을 띄우지 않고 "설정 시작" 액션이 붙은 알림으로 안내한다.
- 오류는 종료 코드가 아니라 알림 심각도(정보/경고/오류)로 전달한다.

## 구조

의존 방향은 `UI -> Application -> Domain`이고, `Infrastructure`는 Domain이 정의한 포트를 구현한다.

```
domain/          커밋 메시지, 작업 번호, 스프린트 작업, MR 초안, 이슈 링크 규칙 (IntelliJ API 미의존)
application/     유스케이스: 작업 시작, 커밋, MR 생성, 초기 설정, 설정 편집, 상태 전환
infrastructure/  JiraClient·GitLabClient(HttpClient), PasswordSafe 자격 증명, 설정 저장소, git 실행
ui/              Settings 패널, 마법사·작업·커밋·MR·검색 다이얼로그와 액션, 툴 윈도우, 알림
```

자세한 설계는 [ARCHITECTURE.md](ARCHITECTURE.md)에 있다.

## 빌드 및 실행

```bash
./gradlew test
./gradlew build
./gradlew buildPlugin
./gradlew runIde
```

`buildPlugin`은 설치 가능한 `build/distributions/jira-task-plugin-<version>.zip`을 만든다.
`runIde`로 뜬 샌드박스에서 `Tools > Jira Task` 메뉴와 `Settings | Tools | Jira Task`를 확인할 수 있다.

> 이 환경의 `JAVA_HOME`이 JDK 25인 경우 Gradle 8.10.2와 호환되지 않는다. JDK 21을 명시해 실행한다.
> 예: `JAVA_HOME="<JDK 21 경로>" ./gradlew.bat test`

## 작업 기록

Node.js CLI에서 플러그인으로 옮긴 이식 작업(T1~T26)의 단위와 결정 사항은
[MIGRATION-LOG.md](MIGRATION-LOG.md)에, 진행 중인 작업 목록은 [TASKS.md](TASKS.md)에 있다.
