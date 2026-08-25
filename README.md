# jira-task-plugin

`RULE.md`의 Jira 업무 규칙을 IntelliJ IDEA 안에서 강제하는 플러그인이다. 이슈 입력 검증, 요청 초안
미리보기, Story 브랜치 생성, 현재 작업 번호 추적, 커밋 메시지 이슈 키 부착을 IDE 안에서 처리한다.

## 기능

| 기능 | 위치 |
| --- | --- |
| 초기 설정 마법사 | `Tools > Jira Task > Run Setup Wizard`, 설정이 없으면 시작 시 알림 |
| 설정 확인·수정 | `Settings \| Tools \| Jira Task` |
| 이슈 초안 검증·미리보기·생성 | `Tools > Jira Task > Create Issue Draft` |
| 스토리 브랜치 생성 | `Tools > Jira Task > Create Story Branch` |
| 현재 작업 번호 저장 | `Tools > Jira Task > Start Jira Task`, 상태 표시줄 위젯(클릭하면 변경) |
| 현재 상태 한눈에 보기 | 오른쪽 사이드바 `Jira Task` 툴 윈도우(설정·작업 번호·브랜치 + 실행 버튼) |
| 커밋 | `Tools > Jira Task > Commit with Jira Task`, Git 커밋 창의 이슈 키 자동 접두 |

## 설정과 자격 증명

- 설정은 IntelliJ 표준 방식(애플리케이션 레벨 `PersistentStateComponent`, `jira-task.xml`)으로 보관한다.
- Jira API 토큰은 설정 파일에 저장하지 않고 IDE 비밀번호 저장소(PasswordSafe)에만 보관하며,
  이메일과 토큰은 설정 화면에 표시하지 않는다.
- `JIRA_EMAIL`·`JIRA_API_TOKEN` 환경 변수가 있으면 저장된 값보다 먼저 사용한다.
- 초기 설정은 IDE 시작 직후 모달 창을 띄우지 않고 "설정 시작" 액션이 붙은 알림으로 안내한다.
- 오류는 종료 코드가 아니라 알림 심각도(정보/경고/오류)로 전달한다.

## 구조

의존 방향은 `UI -> Application -> Domain`이고, `Infrastructure`는 Domain이 정의한 포트를 구현한다.

```
domain/          RULE.md 규칙, 검증, 브랜치·커밋·작업 번호, 정책 설정 (IntelliJ API 미의존)
application/     유스케이스: 검증, 초안, 이슈 생성, 브랜치 생성, 작업 시작, 커밋, 초기 설정, 설정 편집
infrastructure/  JiraClient(HttpClient), PasswordSafe 자격 증명, 설정 저장소, git 실행
ui/              Settings 패널, 마법사·이슈·브랜치·작업·커밋 다이얼로그와 액션, 툴 윈도우, 알림
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
