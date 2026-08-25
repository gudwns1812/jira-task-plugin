# jira-task-plugin 아키텍처

> 이 문서는 `RULE.md`의 Jira 운영 규칙을 IntelliJ IDEA 안에서 강제하는 플러그인의 구현 설계서다.
> 규칙 판단은 IntelliJ API를 모르는 순수 Kotlin 코드에 두고, IDE는 그 결과를 보여 주는 껍데기로 둔다.

## 1. 목표와 범위

### 목표

사용자가 IDE 안에서 에픽·스토리·작업을 만들 때 다음을 자동으로 확인하고 처리한다.

- 제목, 이슈 유형, 필수 입력값, 설명 형식이 `RULE.md`를 만족하는지 검증한다.
- 유형별 기본 레이블과 설명 템플릿을 자동 적용한다.
- 작업의 스토리 포인트와 담당 영역의 일관성을 검증한다.
- 검증을 통과한 입력으로 Jira 생성 요청 초안을 만들고, 확인하면 실제로 이슈를 만든다.
- 스토리 브랜치 생성, 현재 작업 번호 추적, 커밋 메시지 이슈 키 부착을 IDE 흐름에 붙인다.

### 범위

- 초기 설정 마법사: Jira 연결 확인, 프로젝트·이슈 유형·진행 상태 선택
- 설정 화면: 저장된 값 확인과 수정 (`Settings | Tools | Jira Task`)
- 이슈 초안: 입력 검증 → 요청 본문 미리보기 → 이슈 생성
- 스토리 브랜치 생성과 Jira 상태 전환
- 현재 작업 번호 저장(상태 표시줄 위젯)과 커밋 메시지 이슈 키 부착
- 툴 윈도우: 설정·작업 번호·브랜치 상태를 한 화면에서 보고 액션을 실행

### 비범위

- 서브 작업 생성: 규칙상 지원하지 않는다.
- 에픽·스토리에 스토리 포인트 입력: 규칙상 지원하지 않는다.
- 툴 윈도우의 이슈 목록·검색, 스프린트 생성, 일괄 수정
- Jira 외 프로젝트 관리 도구 연동

## 2. 기술 선택

| 역할 | 선택 | 이유 |
| --- | --- | --- |
| 언어 | Kotlin (JVM 21) | IntelliJ 플러그인 표준이며 도메인 규칙을 타입으로 표현하기 쉽다 |
| 빌드 | Gradle + `org.jetbrains.intellij.platform` 2.x | 플러그인 빌드·샌드박스 실행·검증을 한 번에 제공한다 |
| 대상 IDE | IntelliJ IDEA Community 2024.2+ (`pluginSinceBuild = 242`) | `gradle.properties`에서 관리한다 |
| HTTP | 내장 `java.net.http.HttpClient` | Jira REST API 호출에 충분하고 외부 의존성이 없다 |
| JSON | 얇은 자체 파서·직렬화(`infrastructure/JsonSupport.kt`) | 플러그인 배포에 라이브러리를 끌어들이지 않는다 |
| 설정 저장 | 애플리케이션 레벨 `PersistentStateComponent` (`jira-task.xml`) | IntelliJ 표준 저장 위치를 그대로 쓴다 |
| 자격 증명 | `PasswordSafe` | API 토큰을 설정 파일과 분리한다 |
| 테스트 | JUnit 4 + IntelliJ Platform test framework | 도메인은 순수 단위 테스트, UI는 플랫폼 픽스처로 검증한다 |

`kotlin.stdlib.default.dependency = false`로 두어 IDE가 제공하는 stdlib를 쓴다.

## 3. 사용자 경험과 진입점

모든 기능은 `Tools > Jira Task` 메뉴에 모으고, 툴 윈도우 툴바는 같은 액션을 **참조**해 동작이 갈라지지 않게 한다.

| 진입점 | 책임 | Jira API 호출 |
| --- | --- | --- |
| `Run Setup Wizard` | Jira 연결 확인과 프로젝트·유형·상태 선택 | 읽기 전용 조회 |
| `Settings \| Tools \| Jira Task` | 저장된 설정 확인·수정 | 없음(저장 시 검증만) |
| `Create Issue Draft` | 입력 검증 → 요청 본문 미리보기 → 이슈 생성 | 조회·생성·연결 |
| `Create Story Branch` | 스토리 확인 → 브랜치 생성 → 진행 상태 전환 | 조회·전환 |
| `Start Jira Task` | 커밋에 쓸 현재 작업 번호를 저장소별로 저장 | 없음 |
| 상태 표시줄 위젯 | 현재 작업 번호 표시, 클릭하면 변경 | 없음 |
| 커밋 창 | 커밋 메시지에 이슈 키 자동 접두 | 없음 |
| `Jira Task` 툴 윈도우 | 설정·작업 번호·브랜치 상태 표시와 액션 실행 | 없음 |

이슈 생성은 내부에서 항상 검증기와 초안 생성기를 거친다. 미리보기와 실제 생성의 규칙이 갈라지는 일을 막는다.

## 4. 입력 형식

이슈 입력은 다이얼로그 폼으로 받되, 도메인은 폼이 아니라 `IssueInput` 값 객체를 받는다.
폼과 도메인 사이의 변환은 `ui/issue/IssueDraftPresenter.kt`가 담당하고, 도메인은 UI를 모른다.

작업 입력이 담는 값은 다음과 같다.

```text
type         epic | story | task
epicKey      에픽 키 (스토리·작업)
storyKey     스토리 키 (작업, 선택)
title        [AUTH-001 | FE] 이메일 회원가입 화면 구현
assignee     담당자
priority     우선순위
storyPoints  1 | 2 | 3 | 5 | 8
sprint       스프린트 이름
dueDate      YYYY-MM-DD
description  summary + deliverables[]
```

설명은 Jira가 받는 ADF(Atlassian Document Format)로 변환한다(`domain/jira/AdfDocument.kt`).

```text
## 설명
회원가입 화면과 이메일·비밀번호 입력 상태를 구현한다.

## 결과물
[] 이메일·비밀번호 입력 및 오류 상태가 표시된다.
[] 디자인 시안과 일치하는 회원가입 화면이 구현된다.
```

## 5. 디렉터리 구조

```text
src/main/kotlin/io/github/gudwns1812/jiratask/
├─ domain/                     # IntelliJ API를 import하지 않는 순수 규칙 코드
│  ├─ input/                   # IssueInput 값 객체와 파서
│  ├─ validation/              # RULE.md 검증기, 제목 파서, 검증 결과
│  ├─ jira/                    # 요청 초안, ADF, Jira 포트(Reader/Creator/Transitioner)
│  ├─ branch/                  # 브랜치 이름 규칙과 Git 포트
│  ├─ commit/                  # 커밋 메시지 조립
│  ├─ task/                    # 작업 번호
│  ├─ template/                # 이슈 설명 템플릿
│  └─ policy/                  # 정책 설정(허용 영역·분류·포인트·레이블)
├─ application/                # 유스케이스: 흐름만 담당, 화면을 모른다
│  ├─ ValidateIssue.kt  CreateDraft.kt  CreateIssue.kt
│  ├─ CreateBranch.kt   StartTask.kt    AdvanceTask.kt  CreateCommit.kt
│  └─ RunInitialSetup.kt  EditSettings.kt
├─ infrastructure/             # 포트 구현
│  ├─ JiraClient.kt            # HttpClient 기반 Jira REST 어댑터
│  ├─ CredentialStore.kt       # PasswordSafe 자격 증명
│  ├─ JiraTaskConfigStorage.kt # PersistentStateComponent 설정 저장소
│  ├─ PolicyConfigRepository.kt
│  ├─ GitRepository.kt         # git 실행 어댑터
│  └─ JsonSupport.kt
└─ ui/                         # 액션·다이얼로그·설정 패널·툴 윈도우·위젯·알림
src/main/resources/META-INF/plugin.xml
src/test/kotlin/...            # domain·application은 단위, ui는 플랫폼 테스트
```

의존 방향은 한 방향으로만 유지한다.

```text
UI(입출력) -> Application(흐름) -> Domain(규칙)
                    |
                    -> Infrastructure(Jira·설정·Git 구현)
```

- `domain`은 IntelliJ API, 파일 시스템, HTTP를 import하지 않는다. 테스트가 가장 쉬운 순수 코드로 둔다.
- `ui`는 규칙을 직접 판단하지 않는다. 입력을 모아 유스케이스를 호출하고 결과를 표시한다.
- `infrastructure`만 Jira의 필드 ID, HTTP 형식, 인증 방식, IntelliJ 저장소를 안다.
- 포트(인터페이스)는 쓰는 쪽인 `domain`·`application`이 정의하고, 구현만 `infrastructure`에 둔다.

## 6. 핵심 도메인 모델

```kotlin
enum class IssueType { EPIC, STORY, TASK }

data class IssueInput(
    val type: IssueType,
    val title: String,
    val epicKey: String?,
    val storyKey: String?,     // 기획·디자인 등 독립 작업에서는 없을 수 있다
    val assignee: String?,
    val priority: String?,
    val storyPoints: Int?,     // 1, 2, 3, 5, 8
    val sprint: String?,
    val dueDate: String?,      // ISO 8601 날짜(YYYY-MM-DD)
    val description: IssueDescription?,
)
```

영역(`FE`, `BE`, `AI`, `QA`, `INFRA`)과 업무 분류(`PLAN`, `UX`, `DESIGN`, `TECH`, `INFRA`, `DOCS`, `QA`, `PT`),
허용 포인트, 레이블 매핑은 코드 상수가 아니라 `domain/policy/PolicyConfig.kt`가 읽는 정책 설정으로 둔다.

## 7. `RULE.md`를 코드로 옮기는 검증 규칙

아래 규칙은 `domain/validation/IssueValidator.kt`에 작은 함수로 나눈다. 오류는 하나만 반환하지 말고 가능한 한 모두 모아서 보여 준다.

| 규칙 | 적용 대상 | 검증 방식 |
| --- | --- | --- |
| 서브 작업 금지 | 전체 | 입력 유형에 서브 작업을 두지 않는다 |
| 에픽 제목 | epic | 기능 에픽은 `[CODE] 이름`, 지원 에픽은 명사형 제목 허용 |
| 스토리 제목 | story | `^\[[A-Z]+-\d{3}\] .+` 정규식 |
| 기능 작업 제목 | task | `^\[[A-Z]+-\d{3} \| (FE\|BE\|AI\|QA\|INFRA)\] .+` 정규식 |
| 공통 작업 제목 | task | `[PLAN] ...` 또는 `[PLAN \| FE] ...` 형식 및 공식 업무 분류 검사 |
| 작업 필수 항목 | task | epic, 담당자, 우선순위, 포인트, 스프린트, 기한 모두 존재 |
| 에픽·스토리 포인트 금지 | epic, story | `storyPoints`가 있으면 오류 |
| 작업 설명 | task | 설명과 결과물 목록이 비어 있지 않은지 검사 |
| 포인트 허용값 | task | `1, 2, 3, 5, 8`만 허용 |
| 레이블 | story, task | 스토리는 `feat`, 작업은 담당 영역에 맞는 하나의 기본 레이블 (`INFRA` 영역은 `Infra`) |
| 기능 연결 | 기능 작업 | 작업 제목의 기능 ID와 스토리 제목의 기능 ID 일치 확인 |

정규식만으로 "작업이 0.5~2일 규모인지", "완료 기준이 객관적인지"를 확실히 판별할 수 없다. 이 둘은 자동 경고(`warning`)로 시작하고, 오류(`error`)로 막아야 하는 규칙과 구분한다.

## 8. Jira 연동 경계

Jira 프로젝트마다 이슈 타입 이름과 커스텀 필드 ID가 다를 수 있다. 따라서 코드에 `customfield_10016` 같은 값을 박아 넣지 않는다.

- 이슈 유형·상태 ID는 설정 마법사가 조회해 설정에 저장한다.
- 스토리 포인트·스프린트 커스텀 필드는 실행 시점에 `/rest/api/3/field`로 찾는다.
- 스프린트 필드는 **이름이 아니라 필드 타입**(`schema.custom == com.pyxis.greenhopper.jira:gh-sprint`)으로 찾는다.
  Jira 사이트 로케일에 따라 필드 이름이 번역돼 오기 때문이다. 못 찾으면 이름(`sprint`, `스프린트`)으로 되짚는다.
- 스프린트 ID를 스토리에서 얻지 못하면 프로젝트 보드의 진행 중·예정 스프린트를 훑어 이름이 정확히 일치하는 스프린트를 찾는다.
  같은 이름이 둘 이상이면 잘못 넣지 않도록 포기한다.

### 생성 흐름

```text
다이얼로그 입력
  -> IssueInput 조립
  -> 도메인 규칙 검증 (오류가 있으면 여기서 멈춘다)
  -> 요청 본문(초안) 생성 및 미리보기
  -> 확인 시 Jira 이슈 생성
  -> 스토리 연결·커스텀 필드 반영 (실패는 오류가 아니라 경고로 알린다)
```

넣지 못한 값은 조용히 버리지 않고 "Jira에서 직접 채워야 하는 값"으로 이유와 함께 보고한다.

## 9. Git 브랜치 생성

브랜치는 **스토리에서만** 생성한다. 에픽에서는 브랜치를 만들지 않으며, 작업은 생성된 스토리 브랜치에서 수행한다. 이는 `RULE.md`의 브랜치 운영 기준을 그대로 따른다.

### 브랜치 이름 규칙

```text
feat/<스토리 이슈 번호의 마지막 숫자>-<짧은 영문 작업명>

예: S15P11A108-100 + signup -> feat/100-signup
```

- 스토리 번호는 현재 설정된 프로젝트 키와 조립해 `S15P11A108-100`으로 조회한다.
- Jira에서 해당 이슈를 조회해 실제 유형이 설정된 스토리 타입인지 확인한다. 스토리가 아니면 생성하지 않는다.
- 작업명은 소문자 영문·숫자·하이픈만 허용한다. 예: `signup`, `email-login`, `sso-auth`.
- 작업 디렉터리에 커밋되지 않은 변경 사항이 있거나 같은 이름의 로컬·원격 브랜치가 있으면 중단한다.
- 브랜치를 만든 뒤 Jira가 제공한 전환 목록에서 설정된 진행 상태로 향하는 전환을 찾아 적용한다. 이미 진행 중이면 전환 호출 없이 성공한다.
- 브랜치 생성 후 Jira 전환이 실패하면 생성한 브랜치는 보존하고 경고 알림으로 알린다.

### 생성 흐름

```text
Create Story Branch (스토리 100, 이름 signup)
  -> 설정된 프로젝트 키와 조립해 S15P11A108-100 결정
  -> Jira에서 이슈 조회 및 스토리 유형 확인
  -> 스토리 번호와 작업명으로 브랜치명 결정
  -> Git 저장소인지 확인
  -> 작업 디렉터리 변경 사항·동명 브랜치 확인
  -> git switch -c feat/100-signup
  -> Jira 전환 목록 조회 후 진행 상태 전환
  -> 생성된 브랜치명과 스토리 상태 알림
```

## 10. 작업 번호와 커밋

- `Start Jira Task`는 현재 작업 번호를 **저장소별 로컬 Git 설정**에 저장한다. 커밋되지 않으므로 팀원과 섞이지 않는다.
- 커밋 메시지는 저장된 작업 번호와 설정된 프로젝트 키를 조합해 `S15P11A108-257 <메시지>` 형태로 만든다.
- 커밋 창이 열릴 때 `vcs.commitMessageProvider`가 기본 메시지를 채우고, `checkinHandlerFactory`가 커밋 직전에 이슈 키를 한 번 더 보정한다.
  모달 커밋 다이얼로그와 커밋 툴 윈도우가 같은 경로를 쓰게 하기 위해서다.

## 11. 설정 및 보안

- 설정은 애플리케이션 레벨 `PersistentStateComponent`(`jira-task.xml`)에 JSON 원문으로 저장한다.
  검증·병합은 `domain/policy/PolicyConfig.kt`가 순수 함수로 담당하고, 저장소는 얇은 어댑터로 둔다.
- 설정이 없으면 IDE 시작 직후 모달 창을 띄우지 않고 "설정 시작" 액션이 붙은 알림으로 안내한다.
- 마법사는 인증을 확인한 뒤 접근 가능한 프로젝트와 이슈 유형별 상태를 조회해 선택받는다.
  스토리·작업·에픽 이슈 유형은 이름으로 자동 판별하고, 진행 상태만 직접 고른다.
- API 토큰은 설정에 저장하지 않고 `PasswordSafe`에 보관한다. 서비스 이름은 `jira-task-plugin`, 계정 식별자는 `baseUrl|email` 조합이다.
- 환경 변수 `JIRA_EMAIL`, `JIRA_API_TOKEN`은 저장된 자격 증명보다 우선한다.
- 설정 화면은 이메일과 토큰을 표시하지 않는다.
- 네트워크·인증 오류에도 토큰 원문이나 HTTP `Authorization` 헤더를 로그나 알림에 출력하지 않는다.

## 12. 오류 전달

오류는 종료 코드가 아니라 알림 심각도로 전달한다. 문구는 사용자가 바로 고칠 수 있도록 `대상: 이유: 기대 형식` 순으로 쓴다.

```text
검증 실패 (2개)
- title: 작업 제목의 담당 영역은 FE, BE, AI, QA, INFRA 중 하나여야 합니다.
  입력값: [AUTH-001 | Frontend] 회원가입 화면 구현
- dueDate: YYYY-MM-DD 형식의 기한이 필요합니다.
```

| 상황 | 전달 방식 |
| --- | --- |
| 입력 형식·업무 규칙 위반 | 다이얼로그 안 검증 오류(창을 닫지 않는다) |
| 설정 누락 | 경고 알림 + "설정 시작" 액션 |
| Jira 인증·권한 오류(`JiraAuthenticationError`) | 오류 알림 |
| Jira 네트워크·서버 오류(`JiraNetworkError`) | 오류 알림 |
| 부분 성공(브랜치는 만들었으나 전환 실패, 이슈는 만들었으나 필드 누락) | 경고 알림 + 남은 할 일 안내 |
| Git 저장소·브랜치·작업 디렉터리 상태 오류 | 오류 알림 |

## 13. 작업 방식

1. 도메인 규칙을 먼저 순수 코드로 바꾸고 단위 테스트로 고정한다. Jira 테스트 이슈를 만들지 않아도 규칙을 안정화할 수 있다.
2. 유스케이스는 가짜 포트 구현으로 테스트한다. 실패 경로(권한 없음, 필드 없음, 이름 중복)를 정상 경로와 같은 비중으로 다룬다.
3. UI는 "확인 시 무엇을 넘기는지 / 유효하지 않으면 아무것도 만들지 않는지"를 검증한다.
4. `./gradlew test`로 전체를 돌린 뒤 `./gradlew runIde` 샌드박스에서 실제 흐름을 확인한다.
5. 실제 Jira에서 확인이 필요한 변경은 테스트 프로젝트에서 먼저 만들어 보고, 관찰한 증상과 원인을 [MIGRATION-LOG.md](MIGRATION-LOG.md) 또는 [TASKS.md](TASKS.md)에 남긴다.

## 14. 완료 기준

- 잘못된 제목, 서브 작업, 누락된 작업 필수값을 검증기가 정확히 막는다.
- 초안 미리보기에 이슈 타입, 제목, 설명, 레이블, 스프린트, 기한, 포인트가 포함된다.
- 스토리 키 `S15P11A108-100`와 이름 `signup`으로 `feat/100-signup` 브랜치가 생성되고 전환된다.
- 에픽·작업 키, 더러운 작업 디렉터리, 이미 존재하는 브랜치에서는 안전하게 브랜치 생성을 중단한다.
- 테스트 Jira 프로젝트에서 에픽 1개, 스토리 1개, 작업 1개가 올바른 관계와 필드로 생성된다.
- API 토큰이 소스 코드·설정 파일·로그에 남지 않는다.
- `./gradlew test`가 전부 통과하고 `buildPlugin` 산출물이 샌드박스에서 동작한다.
