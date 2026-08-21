# IntelliJ 플러그인 마이그레이션 작업 목록

`task-jira-cli`(Node.js CLI)의 기능을 IntelliJ 플러그인(`intellij-plugin/`)으로 옮기기 위한 작업 분해서다.
각 작업은 독립적으로 구현·테스트·리뷰할 수 있는 단위로 쪼갰다. 괄호 안은 이식 대상이 되는 기존 CLI 소스 파일이다.

작업 순서는 위에서 아래로 진행하는 것을 권장한다(하단 작업은 상단 작업의 결과물에 의존한다).
모든 작업은 `AGENTS.md`의 테스트 우선 완료 기준(정상 동작 테스트 + 엣지 케이스 테스트 + 전체 테스트 통과)을 그대로 따른다.

## 코드 스타일: top-level 함수 대신 메서드/클래스로

T1~T17을 처음 구현할 때 CLI 원본(TypeScript의 순수 함수 스타일)을 그대로 옮겨 도메인/애플리케이션 계층 대부분을 top-level 함수로
작성했었다. 코틀린답지 않다는 피드백을 받아 T18 착수 전에 전체를 다음 규칙으로 리팩터링했다(T1~T17 절 각각에 반영 완료):

- **생성/파싱처럼 특정 타입을 만들어내는 함수**는 그 타입의 companion 팩토리로: `WorkPolicy.resolve(raw)`, `JiraTaskConfig.resolve(raw)`, `JiraTaskConfig.default()`.
- **기존 인스턴스의 데이터만으로 계산하는 함수**는 그 타입의 멤버/확장 함수로: `policy.isArea(x)`, `input.jiraDescription()`, `config.toRawMap()`, `config.mergeWith(update)`.
- **하나의 자연스러운 소유 타입이 없는 상태 없는 알고리즘 묶음**은 이름 있는 `object` 네임스페이스로: `TitleParser`, `IssueValidator`, `IssueInputParser`, `BranchNames`, `CommitMessages`, `TaskNumbers`, `IssueTemplates`.
- **외부 협력자(Jira 리더, Git 저장소 등)가 필요한 유스케이스**는 생성자로 의존성을 주입받는 클래스로: `CreateBranchUseCase(storyIssueReader, gitRepository)`, `StartTaskUseCase(gitRepository)`, `CreateCommitUseCase(gitRepository)`, `RunInitialSetupUseCase(prompter, createJira, saveConfig, credentials)`. 협력자가 없는 `ValidateIssueUseCase`/`CreateDraftUseCase`는 `object`로 남겼다.
- **비공개 구현 세부사항**(예: `PolicyConfig.kt`의 `rejectUnknownKeys`, `readUniqueStrings` 등)은 여전히 private top-level 함수로 둔다 — 호출부에서 보이는 공개 API가 아니므로 이 규칙의 대상이 아니다.

T18부터 새로 작성하는 코드도 이 네 가지 규칙을 따른다.

## 0. CLI → 플러그인 기능 매핑 개요

| CLI 명령/기능 | 플러그인에서의 형태 |
| --- | --- |
| `config setup` (대화형 마법사) | Settings 화면 진입 시 또는 별도 액션으로 실행하는 Wizard 다이얼로그 |
| `config show` / `config set` / `config init` | **Settings \| Tools \| Jira Task** 설정 패널 |
| `template <story\|task>` | 이슈 생성 다이얼로그의 설명 필드 placeholder/도움말 |
| `validate --file` | 이슈 생성 다이얼로그의 실시간 폼 검증 |
| `create --dry-run` | 이슈 생성 다이얼로그의 "미리보기"(요청 초안 JSON 표시) + 실제 생성 액션 |
| `branch create` | "Create Story Branch" 액션(다이얼로그 + Git4Idea 연동) |
| `task start` | 프로젝트별 현재 작업 번호 상태(액션 또는 툴바 위젯) |
| `commit` | Git 커밋 메시지 자동 완성(체크인 핸들러) 또는 "Commit with Jira Task" 액션 |
| API 토큰 저장(OS 자격 증명) | IntelliJ `PasswordSafe` API |
| 종료 코드(0~5) | `Notifications`/`Messages` 다이얼로그 + 로그, 예외 타입으로 구분 |

---

## 1단계 — 도메인 레이어 포팅 (순수 Kotlin, IntelliJ API 미의존)

### T1. 프로젝트 모듈 구조 준비 — (완료)
- **내용**: `intellij-plugin/src/main/kotlin/.../domain`, `application`, `infrastructure` 패키지 골격 생성. `build.gradle.kts`에 필요 시 순수 Kotlin 테스트 의존성(kotlin.test 또는 기존 JUnit4) 확인.
- **완료 기준**: 빈 패키지 + `./gradlew build` 통과.
- **산출물**: 패키지 골격(`domain/{policy,validation,input,branch,commit,task,template,jira}`, `application`, `infrastructure`).

### T2. `WorkPolicy` 도메인 모델 이식 (`src/domain/policy/work-policy.ts`) — (완료)
- **내용**: `Area`, `WorkCategory`, `StoryPoint`, `WorkPolicy` data class와 `DEFAULT_WORK_POLICY`, `isArea`/`isWorkCategory`/`labelForArea`/`labelForCategory` 함수를 Kotlin으로 이식.
- **완료 기준**: 기본 정책 값이 CLI와 동일(areas: FE/BE/AI/QA/INFRA, workCategories: PLAN/UX/DESIGN/TECH/INFRA/DOCS/QA/PT, storyPoints: 1/2/3/5/8, INFRA 영역 레이블 "Infra" 등). 단위 테스트: 기본값 검증, 커스텀 정책에서의 판별 함수 동작, 존재하지 않는 카테고리 레이블 조회 시 null 반환.
- **산출물**: `domain/policy/WorkPolicy.kt`, `domain/policy/WorkPolicyTest.kt` (10 tests).

### T3. 제목 파서 이식 (`src/domain/validation/title-parser.ts`) — (완료)
- **내용**: `parseEpicTitle`, `parseStoryTitle`, `parseTaskTitle`를 정규식 기반으로 이식. `ParsedEpicTitle`(feature/support), `ParsedStoryTitle`, `ParsedTaskTitle`(feature/common) sealed class로 표현.
- **완료 기준**: 정상 케이스(`[AUTH-001] 이메일 회원가입`, `[AUTH-001 | FE] ...`, `[PLAN] ...`, `[PLAN | FE] ...`, 지원 에픽 명사형 제목) + 엣지 케이스(대괄호 누락, 소문자 도메인 코드, 시퀀스 자릿수 다름, 빈 문자열) 테스트.
- **산출물**: `domain/validation/TitleParser.kt`, `domain/validation/TitleParserTest.kt` (10 tests).

### T4. 이슈 검증기 이식 (`src/domain/validation/issue-validator.ts`, `validation-result.ts`) — (완료)
- **내용**: `validateIssue(input, policy)` — 에픽/스토리/작업별 제목 검증, 서브태스크 금지, storyPoints는 작업에만 허용, 작업 설명/결과물 필수, 레이블 도출(영역/업무 분류), 경고(warning)와 오류(error) 분리.
- **의존**: T2, T3.
- **완료 기준**: CLI의 `issue-validator.test.ts`에 대응하는 모든 케이스를 포팅 — 정상 통과, 잘못된 제목 형식, 에픽/스토리에 storyPoints 존재, 알 수 없는 영역/업무분류, 빈 설명/결과물, 짧은 결과물 경고.
- **산출물**: `domain/validation/IssueValidator.kt`, `domain/validation/ValidationResult.kt`, `domain/validation/IssueValidatorTest.kt` (10 tests). 참고: `IssueInput.Epic`/`Story`는 애초에 `storyPoints` 필드가 없어(sealed class) CLI의 런타임 `Object.hasOwn` 검사가 컴파일 타임에 불가능해지므로 이식하지 않음.

### T5. 이슈 입력 스키마/타입 이식 (`src/domain/input/issue-input.types.ts`, `issue-input.schema.ts`) — (완료)
- **내용**: `IssueInput`(epic/story/task) sealed class와 JSON 역직렬화(kotlinx.serialization 또는 Jackson) + 필수 필드/형식 검증(날짜 `YYYY-MM-DD`, storyPoints 허용값 등).
- **완료 기준**: 정상 JSON 파싱, 필수 필드 누락, 잘못된 날짜 형식, 허용되지 않는 storyPoints 값에 대한 테스트.
- **산출물**: `domain/input/IssueInput.kt`(타입), `domain/input/IssueInputParser.kt`(검증), `domain/input/IssueInputParserTest.kt` (11 tests). 범위 결정: 실제 JSON 텍스트 파싱(파일 읽기)은 인프라 계층 책임으로 남기고, 여기서는 이미 역직렬화된 `Map`/`List` 구조를 검증·변환하는 것까지만 담당(CLI의 zod 스키마가 `JSON.parse`된 값을 검증하는 것과 동일한 경계).

### T6. 브랜치 이름·스토리 키 도메인 이식 (`src/domain/branch/branch-name.ts`) — (완료)
- **내용**: `createStoryKey(projectKey, storyNumber)`, `createBranchName(storyNumber, name)` — 프로젝트 키 조합, `--name` 소문자·숫자·하이픈 검증, `feat/<번호>-<name>` 형식 생성. `BranchNameError` 예외.
- **완료 기준**: 정상 생성(`S15P11A108` + `100` → `S15P11A108-100`, `100`+`signup` → `feat/100-signup`), 잘못된 스토리 번호(비숫자), 잘못된 name 형식(대문자/공백/특수문자) 테스트.
- **산출물**: `domain/branch/BranchName.kt`, `domain/branch/BranchNameTest.kt` (4 tests).

### T7. 커밋 메시지·작업 번호 도메인 이식 (`src/domain/commit/commit-message.ts`, `src/domain/task/task-number.ts`) — (완료)
- **내용**: `createTaskKey(projectKey, taskNumber)`, `createCommitMessage(issueKey, message)` — `S15P11A108-257 이메일 회원가입...` 형식. `TaskNumberError`, `CommitMessageError`.
- **완료 기준**: 정상 조합, 빈 메시지, 잘못된 작업 번호 형식 테스트.
- **산출물**: `domain/commit/CommitMessage.kt`, `domain/task/TaskNumber.kt`, 각각의 테스트 (4 tests, 3 tests).

### T8. 이슈 템플릿·생성 요청 초안 포맷터 이식 (`src/domain/template/issue-template.ts`, `src/domain/jira/create-request.ts`) — (완료)
- **내용**: `getIssueTemplate(type)` 문자열 상수, `formatIssueDescription(input)` 및 `CreateRequestDraft` data class(요약/설명/레이블/담당자/우선순위/기한/포인트/스프린트/관계 키).
- **의존**: T4, T5.
- **완료 기준**: story/task 템플릿 문자열이 CLI와 동일. 작업 설명이 `## 설명 / ## 결과물` 마크다운으로 정확히 생성되는지 테스트.
- **산출물**: `domain/template/IssueTemplate.kt`, `domain/jira/CreateRequestDraft.kt`, 각각의 테스트 (2 tests, 4 tests).

### T9. Jira 정책 설정 스키마 이식 (`src/domain/policy/policy-config.ts`) — (완료)
- **내용**: `JiraSettings`(baseUrl/email/project/issueTypes/statuses), `JiraTaskConfig`, `resolveWorkPolicy`, `resolveJiraTaskConfig`, `mergeLabels` 검증 로직(프로젝트 키 정규식, URL 검증, 중복 값 금지, 허용되지 않은 커스텀 레이블 키 거부).
- **의존**: T2.
- **완료 기준**: 기본값 병합, 잘못된 프로젝트 키 형식, 허용 목록에 없는 레이블 키, 중복된 areas/workCategories/storyPoints에 대한 `PolicyConfigurationError` 발생 테스트.
- **산출물**: `domain/policy/PolicyConfig.kt`, `domain/policy/PolicyConfigTest.kt` (11 tests).

> **1단계 진행 상황**: T1~T9 전체 완료. `./gradlew test` 기준 도메인 계층 신규 테스트 65개 + 기존 플레이스홀더 액션 테스트 3개, 총 68개 모두 통과. `./gradlew build`도 통과 확인.
> 참고: 이 환경의 `JAVA_HOME`이 JDK 25로 설정되어 있어 Gradle 8.10.2와 호환되지 않는다. Gradle 명령은 `JAVA_HOME="<JDK 21 경로>" ./gradlew.bat ...`처럼 JDK 21을 명시해 실행해야 한다.

---

## 2단계 — 인프라 레이어 포팅 (IntelliJ Platform API 연동)

### T10. Jira REST 클라이언트 이식 (`src/infrastructure/jira-client.ts`) — (완료)
- **내용**: `verifyIdentity`, `listProjects`(페이지네이션 포함), `listIssueTypesWithStatuses`, `getIssue`, `findTransitionId`, `transition`을 Kotlin + `java.net.http.HttpClient`(또는 IntelliJ `com.intellij.util.io.HttpRequests`)로 이식. Basic Auth 헤더 구성, 401/403 → 인증 오류, 5xx/네트워크 실패 → 네트워크 오류, 404 → 요청 오류로 분류하는 예외 계층(`JiraAuthenticationError`, `JiraNetworkError`, `JiraRequestError`) 포함.
- **완료 기준**: Mock HTTP 서버(또는 인터페이스 스텁)로 정상 응답, 401 인증 실패, 500 네트워크 오류, 잘못된 응답 스키마(필드 누락) 케이스 테스트. 토큰 원문이 로그/예외 메시지에 노출되지 않는지 확인.
- **산출물**: `domain/jira/StoryIssueReader.kt`, `domain/jira/SetupReader.kt`(도메인 포트, `JiraNamedValue`의 정본 위치로 정리 — `PolicyConfig.kt`가 이를 재사용하도록 리팩터링), `infrastructure/JiraClient.kt`(`JiraHttpTransport` 인터페이스로 실제 네트워크와 분리해 테스트 가능하게 구현, JSON 파싱은 IntelliJ 플랫폼에 이미 포함된 Gson 사용), `infrastructure/JiraClientTest.kt` (6 tests, 실제 네트워크 호출 없이 스크립트 응답으로 검증).

### T11. 자격 증명 저장소 이식 (`src/infrastructure/keyring-credential-store.ts`) — (완료)
- **내용**: IntelliJ `com.intellij.ide.passwordSafe.PasswordSafe`를 사용해 `save(baseUrl, email, apiToken)` / `get(baseUrl, email)` 구현. 서비스 이름은 `task-jira-cli` 유지, 계정 식별자는 `baseUrl|email` 조합(대소문자 정규화).
- **완료 기준**: 저장 후 조회 성공, 존재하지 않는 계정 조회 시 `null` 반환, PasswordSafe 접근 실패 시 `CredentialStorageError` 발생 테스트(가능하면 in-memory PasswordSafe 테스트 픽스처 사용).
- **산출물**: `infrastructure/CredentialStore.kt`(`PasswordSafeCredentialStore`), `infrastructure/CredentialStoreTest.kt` (3 tests, `BasePlatformTestCase` 기반 — 플랫폼이 테스트 중 자동으로 제공하는 인메모리 `PasswordSafe`를 사용).

### T12. 설정 저장소 이식 (`src/infrastructure/policy-config-repository.ts`) — (완료)
- **내용**: 로컬 JSON 파일(`~/.jira-task/policy.json`) 대신 또는 병행하여 IntelliJ `PersistentStateComponent`(애플리케이션 레벨 또는 프로젝트 레벨)로 `JiraTaskConfig` 저장. 기존 CLI가 만든 `~/.jira-task/policy.json`을 그대로 읽어 호환할지 여부를 결정하고 문서화.
- **의존**: T9.
- **완료 기준**: 설정 저장/로드 왕복 테스트, 파일이 없을 때 기본값 반환, 손상된 설정에 대한 오류 처리. CLI와 설정 파일을 공유하는 경우 상호 운용 테스트 추가.
- **결정**: CLI의 `~/.jira-task/policy.json`과 자동으로 상호 운용하지 않기로 함 — 플러그인은 IntelliJ 표준 방식(애플리케이션 레벨 `PersistentStateComponent`, XML 저장소 `jira-task.xml`)으로 별도 보관한다. 같은 머신에서 CLI와 플러그인을 함께 쓸 경우 설정을 각각 한다(추후 필요하면 "CLI 설정 가져오기" 기능을 별도 작업으로 추가 가능).
- **산출물**: `domain/policy/PolicyConfig.kt`에 `toRawMap()`/`mergeConfigUpdate()` 추가(직렬화 및 CLI와 동일한 얕은 병합 규칙 — 예: `baseUrl`만 갱신해도 `issueTypes`/`statuses`는 보존), `infrastructure/JiraTaskConfigStorage.kt`(`@Service(APP)` + `@State`로 JSON 원문만 보관하는 얇은 어댑터), `infrastructure/JsonSupport.kt`(Gson `JsonElement` ↔ `Map`/`List` 변환), `infrastructure/PolicyConfigRepository.kt`. 테스트: `domain/policy/PolicyConfigTest.kt`에 라운드트립/병합 케이스 추가, `infrastructure/PolicyConfigRepositoryTest.kt` (4 tests), `infrastructure/JsonSupportTest.kt` (2 tests).
- **버그 수정 기록**: `Any?.toJsonElement()` 최초 구현에서 `JsonArray().apply { forEach { add(...) } }` 형태로 작성했다가, `apply` 블록 안의 암묵적 `this`가 방금 만든 빈 `JsonArray`로 바뀌면서 그 자신(빈 배열)을 순회하는 버그가 있었다(`JsonObject`도 `apply { entries... }`로 같은 위험이 있었으나 `entries`라는 멤버가 없어 우연히 외부 스코프로 폴백되어 정상 동작했음). `PolicyConfigRepositoryTest`의 왕복 저장 테스트가 이를 잡아냈다 — 원본 컬렉션을 지역 변수로 받아 순회하도록 수정했다.

### T13. Git 저장소 어댑터 이식 (`src/infrastructure/git-repository.ts`) — (완료, 설계 변경)
- **내용**: 원시 `git` 프로세스 실행 대신 IntelliJ `Git4Idea`(`GitRepositoryManager`, `Git` 서비스) API를 사용해 `ensureRepository`, `ensureCleanWorkingTree`, `ensureBranchAbsent`, `createAndSwitch`, `ensureStagedChanges`, `commit`, `setCurrentTaskNumber`/`getCurrentTaskNumber`(로컬 git config `jira-task.current-task`) 구현.
- **완료 기준**: 테스트용 임시 Git 저장소(JUnit 픽스처 또는 Git4Idea 테스트 프레임워크)로 각 케이스 검증 — 저장소 아님, 변경 사항 존재, 동명 브랜치 존재, 정상 브랜치 생성, 스테이지 변경 없음, 정상 커밋, 작업 번호 저장/조회.
- **설계 변경**: Git4Idea 대신 CLI와 동일하게 `git` 실행 파일을 `ProcessBuilder`로 직접 호출하도록 구현했다. 이유: (1) CLI 자체도 Git 라이브러리 없이 `execFile("git", ...)`만 사용하는 단순한 명령 조합(rev-parse/status/switch/commit 등)이라 Git4Idea의 캐시·리프레시 계층이 필요 없다. (2) Git4Idea를 쓰려면 플러그인에 `Git4Idea` 의존성을 추가하고 그 전용 테스트 프레임워크가 필요해 위험과 설정 비용이 크게 늘어난다. (3) `ProcessBuilder` 방식은 CLI의 기존 통합 테스트(`tests/infrastructure/git-repository.test.ts`)를 실제 임시 Git 저장소로 그대로 재현할 수 있어 동작 동등성을 더 확실히 검증할 수 있다. 추후 IDE의 VCS 상태 갱신과 더 긴밀히 통합해야 할 필요가 생기면(예: 브랜치 생성 후 IDE의 Git 위젯이 즉시 갱신되지 않는 문제) Git4Idea로 교체를 재검토한다.
- **산출물**: `domain/branch/GitRepositoryPort.kt`, `infrastructure/GitRepository.kt`, `infrastructure/GitRepositoryTest.kt` (5 tests, 실제 임시 Git 저장소에서 검증).

> **2단계 진행 상황**: T10~T13 전체 완료. `./gradlew test` 기준 전체 95개 테스트 통과, `./gradlew build` 통과.

---

## 3단계 — 애플리케이션(유스케이스) 레이어 포팅

### T14. `validateRawIssue` / `createDraft` 유스케이스 이식 (`src/application/validate-issue.ts`, `create-draft.ts`) — (완료)
- **의존**: T4, T5, T8, T9.
- **완료 기준**: 정상 입력 → 오류 없음 + 레이블 도출, 오류 다건 동시 반환(첫 오류에서 멈추지 않음), `create-draft`가 내부적으로 동일 검증기를 재사용하는지 확인하는 테스트.
- **산출물**: `application/ValidateIssue.kt`, `application/CreateDraft.kt`, 각각의 테스트 (3 tests, 4 tests).

### T15. `createBranch` 유스케이스 이식 (`src/application/create-branch.ts`) — (완료)
- **의존**: T6, T10, T13.
- **완료 기준**: 성공(전환 필요), 성공(이미 진행 중이라 전환 생략), partial-success(브랜치는 생성됐지만 Jira 전환 실패), 실패(스토리 아님/인증 오류/네트워크 오류/Git 상태 오류) 각각 테스트. 브랜치가 생성된 후에는 실패해도 브랜치를 롤백하지 않는 것을 확인.
- **산출물**: `application/CreateBranch.kt`, `application/CreateBranchTest.kt` (10 tests, 가짜 `StoryIssueReader`/`GitRepositoryPort`로 검증).

### T16. `startTask` / `createCommit` 유스케이스 이식 (`src/application/start-task.ts`, `create-commit.ts`) — (완료)
- **의존**: T7, T13.
- **완료 기준**: 작업 번호 저장 성공, 저장된 작업 번호 없이 커밋 시도 시 실패, 스테이지된 변경 없을 때 실패, 정상 커밋 메시지 생성 테스트.
- **산출물**: `application/StartTask.kt`, `application/CreateCommit.kt`, 각각의 테스트 (3 tests, 3 tests).

### T17. `runInitialSetup` 유스케이스 이식 (`src/application/run-initial-setup.ts`) — (완료)
- **내용**: 연결 확인(재시도 루프 포함) → 프로젝트 목록 조회/선택 → 이슈 타입 목록에서 스토리/작업/에픽을 이름으로 자동 매칭(중복/누락 시 `SetupSelectionError`) → 상태 선택 → 요약 확인 → 자격 증명 저장 → 설정 저장까지의 전체 흐름을 UI에 독립적인 `SetupPrompter` 인터페이스로 이식.
- **의존**: T9, T10, T11, T12.
- **완료 기준**: 인증 실패 후 재시도 성공, 접근 가능한 프로젝트 없음, 스토리/작업/에픽 유형 중 하나라도 없거나 중복될 때 실패, 취소 시 아무 것도 저장하지 않음(자격 증명·설정 모두) 테스트.
- **산출물**: `application/RunInitialSetup.kt`(`SetupPrompter`, `CredentialStore` 포트를 포함 — CLI와 같이 애플리케이션 계층이 포트를 정의하고 인프라가 구현하는 방향을 지키기 위해, T11에서 인프라 계층에 두었던 `CredentialStore` 인터페이스를 이곳으로 옮기고 `infrastructure/CredentialStore.kt`는 구현만 담당하도록 리팩터링), `application/RunInitialSetupTest.kt` (7 tests).

> **3단계 진행 상황**: T14~T17 전체 완료. 이로써 1~3단계(도메인·인프라·애플리케이션, T1~T17, 25개 중 17개)가 모두 끝났다. 남은 T18~T25는 Swing UI/Settings/액션 등록 작업으로, 순수 단위 테스트만으로 완결하기 어렵고 `./gradlew runIde`로 직접 실행해 확인해야 하는 부분이 많다.

### T1~T17 리뷰 및 검증 결과

`clean` 상태에서 다시 빌드·테스트하고 코드를 다시 읽어보며 진행한 검증이다.

- `./gradlew clean build`: 전체 재컴파일 포함 성공, 컴파일 경고 0건(`grep -i "warning:"` 결과 없음).
- `./gradlew test`: **126개 테스트 전부 통과**(리뷰 중 회귀 테스트 1건 추가).
- `./gradlew buildPlugin`: 실제 설치 가능한 `jira-task-plugin-0.1.0.zip` 패키징 성공 — 이 과정에서 헤드리스 IDE가 실제로 `plugin.xml`을 로드해 인덱싱하므로(`buildSearchableOptions`), 플러그인 디스크립터와 클래스가 실제 IDE에서 로드 가능한지까지 확인됐다.
- `./gradlew verifyPluginStructure verifyPluginProjectConfiguration`: 통과.
- 계층 경계 점검(grep 기반): `domain` 패키지는 IntelliJ API·`java.net.http`·Gson을 전혀 import하지 않음, `application` 패키지는 IntelliJ API나 `infrastructure`를 직접 import하지 않음 — 의존 방향(`CLI(입출력) -> Application -> Domain`, `Infrastructure`는 포트 구현만)이 CLI 원본 설계와 동일하게 유지됨을 확인.
- **리뷰 중 발견해 수정한 이슈 2건**:
  1. `PolicyConfig.kt`의 `resolveJiraTaskConfig`가 `projectKey`/`jira` 오류와 `areas`/`workCategories`/`storyPoints`/`labels` 오류를 두 단계로 나눠 검사하고 있어, 예를 들어 잘못된 `projectKey`와 중복된 `areas`를 동시에 넣으면 첫 번째 오류만 보이고 두 번째는 가려지는 문제가 있었다(CLI는 zod 스키마 하나로 한 번에 모아 보여줌). 두 검사를 하나의 오류 목록으로 합치도록 리팩터링하고, 이를 검증하는 회귀 테스트(`reports an invalid project key together with a duplicate area in one error`)를 추가했다.
  2. `CreateCommit.kt`에서 `createTaskKey`/`createCommitMessage` 실패를 처리하는 코드가 `TaskNumberError`/`CommitMessageError`가 아닌 예외는 다시 던지도록 되어 있었다. CLI는 이 구간의 어떤 오류든 절대 크래시하지 않고 실패 결과로 변환하는 것을 원칙으로 하므로(`catch (error) { ... }`가 모든 오류를 잡음), Kotlin 쪽도 예외 종류를 가리지 않고 항상 `Failure(1, ...)`로 변환하도록 단순화했다.
- 그 외 발견된 사소한 설계 차이(치명적이지 않아 수정하지 않음): `CreateBranchResult.Failure.exitCode`가 CLI의 리터럴 유니온 타입(`1 | 3 | 4 | 5`)이 아니라 일반 `Int`로 느슨하게 타입돼 있다. 컴파일 타임에 잘못된 종료 코드를 막아주지는 못하지만, 현재 이 값을 만드는 곳이 코드 전체에서 4곳뿐이고 모두 테스트로 값이 검증되므로 실질적 위험은 낮다고 판단해 별도 enum 도입은 보류했다.

---

## 4단계 — UI/액션 레이어 (IntelliJ Platform)

### T18. Settings 패널 구현 (`config show` / `config set` / `config init` 대체)
- **내용**: **Settings \| Tools \| Jira Task**에 `Configurable` 추가. 현재 프로젝트 키/Jira 주소/이슈 유형/상태를 읽기 전용으로 표시(이메일·토큰 미노출), 프로젝트 키·베이스 URL 수동 수정 필드, "설정 마법사 다시 실행" 버튼.
- **의존**: T12.
- **완료 기준**: 설정값 표시/저장 UI 테스트(가능하면 `LightPlatformTestCase` 기반), 토큰/이메일이 화면에 노출되지 않는지 확인.

### T19. 초기 설정 마법사 다이얼로그 구현 (`config setup` 대체)
- **내용**: `SetupPrompter`의 IntelliJ 구현체 — Jira 주소/이메일/API 토큰 입력 다이얼로그(토큰은 `JBPasswordField`), 프로젝트/이슈유형/상태 선택 콤보박스, 최종 확인 다이얼로그. 플러그인 최초 사용 시(설정 없음) 자동 트리거 옵션도 포함.
- **의존**: T17, T18.
- **완료 기준**: 정상 흐름 수동 시나리오 테스트 + 취소 시 아무 것도 저장되지 않는 것을 검증하는 테스트(가능한 범위에서 헤드리스 테스트, UI 부분은 수동 검증 문서화).

### T20. 이슈 생성/검증 다이얼로그 구현 (`template` + `validate` + `create --dry-run` 대체)
- **내용**: 이슈 타입(에픽/스토리/작업) 선택 → 타입별 입력 폼(제목, 담당자, 우선순위, 포인트, 스프린트, 기한, 설명/결과물 목록 등) → 입력 변경 시 실시간 검증 결과(오류/경고) 표시 → "미리보기"로 `CreateRequestDraft` JSON 표시. 설명 필드에는 T8의 템플릿을 placeholder로 제공.
- **의존**: T14.
- **완료 기준**: 정상 입력 시 미리보기 정상 생성, 필수값 누락/형식 오류 시 실시간 오류 표시, 경고와 오류 UI 구분 테스트.

### T21. "Create Story Branch" 액션 구현 (`branch create` 대체)
- **내용**: 스토리 번호·작업명(name) 입력 다이얼로그 → T15 유스케이스 실행 → 성공/부분성공/실패를 `Notification`으로 표시. 기존 `CreateJiraIssueAction` 자리표시자를 대체하거나 별도 액션으로 추가(`plugin.xml` 갱신).
- **의존**: T15, T18(설정 값 읽기).
- **완료 기준**: 성공/partial-success/실패 각 케이스에서 올바른 알림 메시지와 심각도(정보/경고/오류)가 표시되는지 테스트.

### T22. 작업 번호 관리 UI 구현 (`task start` 대체)
- **내용**: "Start Jira Task" 액션(작업 번호 입력) + 현재 저장된 작업 번호를 보여주는 상태 표시(예: 상태 표시줄 위젯 또는 툴 윈도우).
- **의존**: T16.
- **완료 기준**: 저장 후 상태 표시 갱신 확인, 저장소가 아닌 프로젝트에서 액션 비활성화 확인.

### T23. 커밋 메시지 자동 완성 연동 (`commit` 대체)
- **내용**: `CheckinHandlerFactory`/`VcsCheckinHandlerFactory`를 구현해 Git 커밋 다이얼로그에서 저장된 작업 번호 + 설정된 프로젝트 키로 이슈 키를 자동 접두(prefix)하거나, 별도 "Commit with Jira Task" 액션 제공.
- **의존**: T16.
- **완료 기준**: 작업 번호 저장됨/안 됨 두 경우의 커밋 메시지 동작 테스트, 스테이지 변경 없을 때의 처리 확인.

---

## 5단계 — 마무리

### T24. `plugin.xml` 정리 및 액션/설정 등록
- **내용**: T21~T23의 액션과 T18의 `Configurable`을 `plugin.xml`에 정식 등록하고, `CreateJiraIssueAction` 자리표시자를 제거하거나 실제 기능으로 교체.
- **완료 기준**: `./gradlew runIde` 샌드박스에서 모든 메뉴 항목이 자리표시자 메시지 없이 실제 동작.

### T25. 통합 테스트 및 README 갱신
- **내용**: 전체 흐름(설정 마법사 → 이슈 검증/미리보기 → 브랜치 생성 → 작업 시작 → 커밋)에 대한 시나리오 테스트 추가. `intellij-plugin/README.md`를 완성된 기능 기준으로 갱신.
- **완료 기준**: `./gradlew test` 전체 통과, `./gradlew build` 통과, `runIde`로 주요 시나리오 수동 확인 기록.

---

## 참고: 기존 CLI 소스 위치

| 레이어 | 경로 |
| --- | --- |
| CLI 진입점 | `src/cli/index.ts` |
| 애플리케이션 유스케이스 | `src/application/*.ts` |
| 도메인 규칙 | `src/domain/**/*.ts` |
| 인프라 어댑터 | `src/infrastructure/*.ts` |
| 규칙 원문 | `RULE.md` |
| 아키텍처 설계서 | `ARCHITECTRUE.md` |
| 기존 테스트(포팅 시 케이스 참고) | `tests/**/*.test.ts` |
