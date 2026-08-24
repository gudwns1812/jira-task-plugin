# jira-task IntelliJ 플러그인

`task-jira-cli`(Node.js CLI)의 기능을 IntelliJ IDEA 안에서 쓰는 플러그인이다. RULE.md의 업무 규칙,
검증 로직, Jira·Git 연동을 CLI와 같은 동작으로 이식했다.

## 기능

| 기능 | 위치 | 대응하는 CLI 명령 |
| --- | --- | --- |
| 초기 설정 마법사 | `Tools > Jira Task > Run Setup Wizard`, 설정이 없으면 시작 시 알림 | `config setup` |
| 설정 확인·수정 | `Settings \| Tools \| Jira Task` | `config show` / `config set` / `config init` |
| 이슈 초안 검증·미리보기 | `Tools > Jira Task > Create Issue Draft` | `template`, `validate --file`, `create --dry-run` |
| 스토리 브랜치 생성 | `Tools > Jira Task > Create Story Branch` | `branch create` |
| 현재 작업 번호 저장 | `Tools > Jira Task > Start Jira Task`, 상태 표시줄 위젯(클릭하면 변경) | `task start` |
| 현재 상태 한눈에 보기 | 오른쪽 사이드바 `Jira Task` 툴 윈도우(설정·작업 번호·브랜치 + 실행 버튼) | — |
| 커밋 | `Tools > Jira Task > Commit with Jira Task`, Git 커밋 창의 이슈 키 자동 접두 | `commit` |

Jira API 토큰은 설정 파일에 저장하지 않고 IDE 비밀번호 저장소(PasswordSafe)에만 보관하며,
이메일과 토큰은 설정 화면에 표시하지 않는다. `JIRA_EMAIL`·`JIRA_API_TOKEN` 환경 변수가 있으면
CLI와 같은 우선순위로 저장된 값보다 먼저 사용한다.

## CLI와 다른 점

- **설정 저장 위치**: CLI의 `~/.jira-task/policy.json`과 공유하지 않고 IntelliJ 표준 방식
  (애플리케이션 레벨 `PersistentStateComponent`, `jira-task.xml`)으로 따로 보관한다. 같은 머신에서
  CLI와 플러그인을 함께 쓰면 설정을 각각 해야 한다.
- **실제 이슈 생성은 없다**: CLI의 `create`도 `--dry-run` 없이는 거부하므로(아직 미구현) 플러그인도
  생성 요청 초안(JSON)까지만 만든다. 확인 버튼은 그 초안을 클립보드로 복사한다.
- **초기 설정 자동 실행**: CLI는 인자 없이 실행하면 곧바로 대화형 설정을 시작하지만, 플러그인은
  IDE 시작 직후 모달 창을 띄우지 않고 "설정 시작" 액션이 붙은 알림으로 안내한다.
- **종료 코드 대신 알림**: CLI의 종료 코드(0~5)는 알림 심각도(정보/경고/오류)로 옮겼다.

## 구조

CLI와 같은 의존 방향(`UI -> Application -> Domain`, `Infrastructure`는 포트 구현)을 유지한다.

```
domain/          RULE.md 규칙, 검증, 브랜치·커밋·작업 번호, 정책 설정 (IntelliJ API 미의존)
application/     유스케이스: 검증, 초안, 브랜치 생성, 작업 시작, 커밋, 초기 설정, 설정 편집
infrastructure/  JiraClient(HttpClient), PasswordSafe 자격 증명, 설정 저장소, git 실행
ui/              Settings 패널, 마법사·이슈·브랜치·작업·커밋 다이얼로그와 액션, 알림
```

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

이식 작업(T1~T26)의 단위와 결정 사항은 [MIGRATION-LOG.md](MIGRATION-LOG.md)에, 진행 중인 작업 목록은 [TASKS.md](TASKS.md)에 있다.
