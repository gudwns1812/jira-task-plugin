# Version 2 개편 작업 목록

지금 플러그인은 **Jira를 대신 조작하는 도구**로 만들어져 있다. 이슈를 검증하고, 초안을 미리 보고,
실제로 생성하고, 커밋만으로 작업을 `완료`로 옮긴다. [Version2.md](Version2.md)와 새 [RULE.md](RULE.md)는
그 전제를 뒤집는다.

> **Jira를 AI로 대신 조작하는 플러그인이 아니라, IntelliJ의 Git workflow에 Jira를 자연스럽게 끼워 넣는 플러그인.**

이 문서는 그 전환의 작업 분해서다. 이전 작업(CLI → 플러그인 이식 T1~T26, 툴 윈도우 개선 W1~W16)의
기록과 코드 스타일 규칙은 [MIGRATION-LOG.md](MIGRATION-LOG.md)에 있다.

모든 작업은 `AGENTS.md`의 테스트 우선 완료 기준(정상 동작 테스트 + 엣지 케이스 테스트 + 전체 테스트 통과)을
따르고, MIGRATION-LOG.md의 코틀린 코드 스타일 규칙(companion 팩토리 / 멤버 함수 / 이름 있는 object /
생성자 주입 클래스)도 그대로 지킨다.

---

## 0. 전제가 무엇이 바뀌었나

| 이전 전제 | 새 전제 (RULE.md 0장) | 무너지는 기능 |
| --- | --- | --- |
| 사용자가 IDE에서 이슈를 만든다 | 사람이 스프린트 전에 Jira에서 전부 만든다 | 이슈 초안·검증·생성 전체 |
| 규칙 위반을 도구가 막아야 한다 | 이슈는 이미 존재한다. 검증할 시점이 없다 | `IssueValidator`, `TitleParser`, `WorkPolicy` |
| 스프린트는 유동적이다 | 확정 후 추가·삭제 금지, 수정만 허용 | 생성·삭제 경로 전부 |
| 브랜치 이름에 스토리 번호를 넣는다 | 내용 요약이면 된다. 규칙 없음 | 스토리 브랜치 생성 전체 |
| 커밋하면 작업이 끝난 것이다 | 완료는 MR 병합에서 automation이 판단한다 | 커밋 후 `완료` 전환 |

핵심은 **"검증"이라는 개념이 통째로 사라진다**는 것이다. 검증은 이슈를 만드는 순간에만 의미가 있는데,
이제 도구는 이슈를 만들지 않는다. 도메인 코드의 절반이 여기에 묶여 있다.

**살아남는 것**: 커밋 메시지에 작업 키를 붙이는 경로(`CommitIssueKey`)와 `진행 중` 전환
(`AdvanceTask`). 이 둘은 새 규칙에서도 그대로 옳고, 앞으로 만들 기능 대부분이 이 위에 얹힌다.

---

## 1. 결정 사항

두 가지 모두 확정되었다. 남은 미결정 사항은 없다.

### D1. 스토리 브랜치 생성(`Create Story Branch`)은 삭제한다 — 확정

RULE.md 5.1에서 **브랜치 이름에 이슈 번호를 넣지 않는다**로 규칙이 바뀌었다. 이제 브랜치 이름은
`feat/signup`처럼 내용 요약이면 되고, Jira와의 연결은 커밋 메시지의 이슈 키가 전부 담당한다.

이 기능은 단순히 Version2.md 목록에 없는 정도가 아니라, **새 규칙을 위반한다.**
`domain/branch/BranchNames`가 `feat/<스토리번호>-<slug>` 형식을 정규식으로 강제하고 있고,
`storyNumberOf()`로 브랜치 이름에서 스토리 번호를 되짚기까지 한다. 규칙이 사라진 형식을
코드가 강제하는 상태이므로 남길 이유가 없다.

→ V4가 이 삭제 작업이다.

### D2. `진행 중` 전환은 플러그인이 맡는다 — 확정

Jira automation에도 커밋을 계기로 `진행 중`으로 옮기는 규칙이 있다. 하지만 **커밋 시점은 이미 늦다.**
작업은 첫 커밋보다 먼저 시작되고, 그 사이 보드에는 아무도 그 일을 하지 않는 것처럼 보인다.

→ 플러그인이 작업 선택 시점에 먼저 옮기고, 커밋 계기의 automation 규칙은 안전망으로 남긴다.
`AdvanceTask` 계열은 **유지한다.** 겹쳐 실행되므로 멱등성이 중요하다 —
`AdvanceTaskUseCase`는 이미 목표 상태이면 `AlreadyThere`를 돌려주므로 이 요구를 만족한다 (RULE.md 4.3).

**`완료` 전환만 걷어낸다.** 그쪽은 MR 병합을 계기로 automation이 처리하는 것이 맞고,
커밋이 곧 완료라는 현재 동작은 사실과 다르다.

---

## 2. 삭제 목록

프로덕션 코드 약 1,900줄, 테스트 약 1,700줄이 사라진다.

### 2.1 이슈 생성·검증 (전부 삭제)

```
main/application/CreateDraft.kt              main/domain/validation/IssueValidator.kt
main/application/CreateIssue.kt              main/domain/validation/TitleParser.kt
main/application/ValidateIssue.kt            main/domain/validation/ValidationResult.kt
main/domain/input/IssueInput.kt              main/domain/template/IssueTemplate.kt
main/domain/input/IssueInputParser.kt        main/domain/policy/WorkPolicy.kt
main/domain/jira/CreateRequestDraft.kt       main/ui/issue/          (9개 파일 전부)
main/domain/jira/JiraDraftReader.kt
main/domain/jira/JiraIssueCreator.kt
```

대응 테스트도 함께 삭제한다.

```
test/application/{CreateDraftTest, CreateIssueTest, ValidateIssueTest}.kt
test/domain/input/{IssueInputTest, IssueInputParserTest}.kt
test/domain/jira/CreateRequestDraftTest.kt
test/domain/policy/WorkPolicyTest.kt
test/domain/template/IssueTemplateTest.kt
test/domain/validation/{IssueValidatorTest, TitleParserTest}.kt
test/ui/issue/                               (4개 파일 전부)
```

**남기는 것**: `domain/jira/AdfDocument.kt`. 이슈 생성에 쓰였지만 Quick Search의 "간단 수정"(V8)이
설명을 ADF로 써야 하므로 필요하다.

### 2.2 스토리 브랜치 생성 (전부 삭제 — D1)

```
main/application/CreateBranch.kt             main/domain/branch/BranchName.kt
main/ui/branch/CreateStoryBranch.kt          main/domain/branch/GitRepositoryPort.kt
main/ui/branch/CreateStoryBranchAction.kt    main/domain/jira/StoryIssueReader.kt
```

```
test/application/CreateBranchTest.kt         test/domain/branch/BranchNameTest.kt
test/ui/branch/CreateStoryBranchTest.kt      test/infrastructure/GitRepositoryBranchTest.kt
test/ui/branch/CreateStoryBranchDialogTest.kt
```

`infrastructure/GitRepository.kt`는 **남긴다.** 커밋과 현재 작업 번호 저장이 여기 있다.
브랜치 전용 함수 세 개(`ensureCleanWorkingTree`, `ensureBranchAbsent`, `createAndSwitch`)만 걷어내고,
`GitRepositoryPort`가 사라지므로 남은 함수를 담을 포트를 `domain/`에 새로 둔다
(`CommitPort` 정도의 이름으로, `setCurrentTaskNumber`/`getCurrentTaskNumber`/`ensureRepository`/
`ensureStagedChanges`/`commit`).

`plugin.xml`에서 `JiraTask.CreateStoryBranch` 액션과 툴바·메뉴 참조를 제거하고,
`JiraTaskAvailability`의 `STORY_BRANCH`와 `storyBranchAvailability()`,
`JiraTaskSettings.forBranch()`/`BranchSettings`도 함께 지운다.
툴 윈도우의 "현재 브랜치" 행은 남긴다 — 브랜치를 만들지 않을 뿐, 어느 브랜치에 있는지는 여전히 유용하다.

### 2.3 `완료` 전환 (D2)

파일 단위 삭제는 없다. `AdvanceTask.kt`, `AdvanceCurrentTask.kt`, `TaskIssueTransitioner.kt`와
`JiraClient`의 `listTransitions`·`transition`·`findTransitionId`는 **`진행 중` 전환에 계속 쓰이므로
전부 남긴다.** 걷어낼 것은 `완료` 쪽 경로뿐이다 (V3).

### 2.4 축소

| 파일 | 무엇을 뺄까 |
| --- | --- |
| `domain/policy/PolicyConfig.kt` (411줄) | `WorkPolicy` 병합·직렬화·검증 경로. Jira 연결 설정만 남긴다 |
| `ui/JiraTaskSettings.kt` | `forIssueDraft()`, `IssueDraftSettings`, `issueTypeIds()` |
| `ui/JiraTaskAvailability.kt` | `JiraTaskCommand.ISSUE_DRAFT` |
| `infrastructure/JiraClient.kt` | `createIssue`, `linkIssues`, `findAccountId` |
| `META-INF/plugin.xml` | `JiraTask.CreateIssueDraft` 액션과 툴바·메뉴 참조 |
| `README.md` / `ARCHITECTURE.md` | "이슈 초안 검증·미리보기·생성"을 전제로 쓰인 문단 전부 |

---

## 3. 목표 기능과 현재 상태

| # | 기능 (Version2.md) | 지금 | 작업 |
| --- | --- | --- | --- |
| 1 | Smart Commit — 스프린트 Task 검색·선택 | 없음 (번호를 직접 타이핑) | V5, V6 |
| 1 | Smart Commit — Jira key 자동 prefix | **있음** (`CommitIssueKey`) | 유지 |
| 1 | Smart Commit — AI 제목 생성 | 없음 | **이번 범위 아님** |
| 2 | Smart MR | 없음 | V9 |
| 2 | Smart MR — AI 제목·본문 | 없음 | **이번 범위 아님** |
| 3 | Task Start — 선택 후 `진행 중` | 부분 (번호를 타이핑, 전환은 **있음**) | V6 |
| 4 | Commit Guard | 없음 (안내만, 검사 없음) | V7 |
| 5 | Jira Quick Search | 없음 | V8 |
| 6 | Jira Key Navigation | 없음 | V10 |

AI 기능은 이번 개편에서 다루지 않는다. 규칙 기반으로 1·3·4·5·6과 MR 생성까지 완성한 뒤,
별도 단계에서 제목·본문 생성을 얹는다. 그래야 AI가 실패해도 플러그인이 여전히 쓸모 있다.

---

## 4. 작업

**V1~V8은 완료했다.** 각 작업 아래에 실제로 무엇이 달라졌는지 적어 두었다.

### V1. 이슈 생성·검증 계열 삭제

**목적**: 2.1의 파일을 전부 지우고 컴파일이 통과하는 상태로 되돌린다.

- 2.1의 프로덕션·테스트 파일 삭제
- `plugin.xml`에서 `JiraTask.CreateIssueDraft` 액션과 툴바·메뉴 참조 제거
- `JiraTaskAvailability`에서 `ISSUE_DRAFT` 제거, `JiraTaskAvailabilityTest` 정리
- `JiraTaskSettings.forIssueDraft()` / `IssueDraftSettings` / `issueTypeIds()` 제거
- 툴 윈도우 툴바(`JiraTaskToolWindow.toolbarGroup()`)에서 해당 액션 참조 제거

**완료 기준**: 빌드·전체 테스트 통과. `JiraTaskPluginRegistrationTest`가 줄어든 액션 목록을 검증한다.
`grep -ri "draft|validate|template" src/main`이 아무것도 찾지 못한다.

**완료** (`e337a5b`). 계획한 파일 외에 `JiraClient`에서 생성 전용 엔드포인트를 함께 걷어냈다 —
`createIssue`·`linkIssues`·`findAccountId`에 더해 `findFieldIds`·`findSprintId`·`storyContext`·
`currentUserName`·`listPriorities`와 커스텀 필드 이름 추측 상수들까지 전부 이슈를 만들 때만 쓰던 것이었다.

### V2. 설정에서 정책(WorkPolicy) 걷어내기

**목적**: 설정 모델을 "Jira 연결 + 프로젝트 키"로 줄인다.

- `WorkPolicy.kt` 삭제, `JiraTaskConfig.policy` 필드 제거
- `PolicyConfig.kt`에서 `areas`/`workCategories`/`storyPoints`/`labels` 읽기·병합·직렬화 제거
- 이전 버전이 저장한 `jira-task.xml`에 정책 값이 남아 있어도 **오류 없이 무시**한다
- 설정 화면(`JiraTaskConfigurable`)에서 정책 항목 제거

**엣지 케이스 테스트**: 정책 키가 남은 옛 설정 파일 로드, 정책 키만 있고 jira 설정이 없는 파일,
빈 파일, 깨진 XML.

**완료 기준**: `PolicyConfigTest`·`PolicyConfigRepositoryTest`가 새 모델로 통과하고,
구버전 설정 파일 픽스처가 마이그레이션 없이 읽힌다.

**완료** (`ef87f6f`). 옛 설정 파일의 정책 키는 허용 목록에 남겨 무시하고, 다시 쓰지도 않는다.
`LEGACY_POLICY_KEYS`가 그 자리다.

### V3. `완료` 자동 전환 제거 (RULE.md 4.2)

**목적**: 커밋이 작업을 `완료`로 옮기지 않게 한다. 완료는 MR 병합을 계기로 automation이 판단한다.
커밋했다고 작업이 끝난 것은 아니다.

- `CommitWithJiraTaskAction`과 `JiraTaskCheckinHandlerFactory`에서 `TaskTarget.DONE` 호출 제거
- `TaskTarget`에서 `DONE` 제거, `TaskTransitionSettings.doneStatus` 제거
- 설정 마법사에서 "완료 상태" 선택 단계 제거 (`SetupWizard`, `SetupForms`)
- `TaskTarget`이 값 하나(`IN_PROGRESS`)만 남으므로, enum을 유지할지 호출부에서 목표 상태를
  직접 넘길지 정리한다
- **`진행 중` 전환은 그대로 둔다** (D2). `AdvanceTaskUseCase`와 `AdvanceCurrentTask`는 유지한다

**엣지 케이스 테스트**: 커밋 성공 후 Jira 상태 전환 호출이 **한 번도 일어나지 않음**을 스파이로 확인.
설정에 완료 상태가 남아 있어도 무시. 작업 시작 시 `진행 중` 전환은 여전히 일어남(회귀 방지).

**완료 기준**: `AdvanceCurrentTaskTest`·`JiraTaskCommitDialogTest`가 새 동작을 검증한다.

**완료** (`7dc18e7`). `TaskTarget` enum은 없앴다 — 값이 하나뿐이라 `AdvanceCurrentTask`가 항상
`진행 중`으로 옮긴다. 옛 설정의 `jira.statuses.task.done`도 정책 키와 같은 방식으로 무시한다.
화면 문구 "스토리 시작 상태"는 "작업 시작 상태"로 바꿨다 — 이제 작업 전환에만 쓰인다.

### V4. 스토리 브랜치 생성 삭제 (RULE.md 5.1)

**목적**: 브랜치 이름 규칙이 사라졌으므로, 이름 형식을 강제하던 기능을 걷어낸다.
브랜치는 IntelliJ의 기본 Git 기능으로 만들면 된다.

- 2.2의 프로덕션·테스트 파일 삭제
- `plugin.xml`에서 `JiraTask.CreateStoryBranch` 액션과 툴바·메뉴 참조 제거
- `JiraTaskAvailability`에서 `STORY_BRANCH`와 `storyBranchAvailability()` 제거
- `JiraTaskSettings.forBranch()` / `BranchSettings` 제거.
  여기서 **스토리** 이슈 유형 요구가 사라진다
- `GitRepositoryPort`를 대체할 포트를 `domain/commit/`에 만들고 `GitRepository`가 그것을 구현하게 한다.
  브랜치 전용 함수 세 개는 지운다
- 설정 마법사의 "스토리 유형" 선택 단계 제거.
  **`진행 중` 상태 선택은 남긴다** — 작업 전환(D2)에 계속 쓰인다.
  V3에서 "완료 상태" 단계를 지우므로 마법사 정리는 두 작업을 함께 본다

**엣지 케이스 테스트**: 커밋 경로가 새 포트로 그대로 동작함(작업 번호 저장·조회, 스테이지 확인, 커밋),
git 저장소가 아닌 디렉터리, 스테이지된 변경 없음.

**완료 기준**: 빌드·전체 테스트 통과. `grep -ri "branch" src/main`이 툴 윈도우의 현재 브랜치 표시와
V9의 MR 코드 외에는 걸리지 않는다.

**완료** (`e9c0ff5`). `StoryIssueReader.kt`에 `JiraIssue`와 Jira 예외 타입들이 함께 들어 있어
파일째 지울 수 없었다 — 인터페이스만 걷어내고 공유 타입은 `domain/jira/JiraIssue.kt`로 옮겼다.
새 포트 이름은 `domain/commit/CommitRepositoryPort`다.

> **V6로 넘긴 것**: 작업 시작 상태를 여전히 **스토리** 이슈 유형의 워크플로에서 고른다
> (`jira.statuses.story.start`). 브랜치가 사라진 지금은 작업 유형의 워크플로에서 고르는 편이 맞다.
> 지금도 동작은 한다 — `AdvanceTaskUseCase`가 ID로 못 찾으면 상태 이름으로 찾기 때문이다.
> 설정 키가 바뀌는 변경이라 Task Start를 손보는 V6에서 함께 처리한다.

### V5. Jira 검색 창구 만들기 (`SprintTaskReader`)

**목적**: 이후 작업 전부가 쓰는 "현재 스프린트의 내 작업" 조회를 한곳에 만든다.

- `domain/jira/SprintTaskReader.kt` — 포트. 두 가지를 노출한다.
  - `currentSprintTasks(projectKey)`: 열린 스프린트의 내 작업 목록
  - `issue(issueKey)`: 이슈 하나 (키·제목·상태·타입·스프린트 소속 여부)
- `JiraClient`에 JQL 검색 구현.

  ```
  assignee = currentUser()
    AND sprint IN openSprints()
    AND issuetype = <작업 유형 ID>
  ```

- **상태로 거르지 않는다.** 끝난 작업도 그대로 가져온다. 거르는 일은 화면에서 한다 (V6).
  - 끝난 작업에 후속 커밋을 다는 일이 흔하고(리뷰 지적 반영, 후속 수정), 그때 그 작업을
    **다시 고를 수단**이 있어야 한다
  - 한 스프린트의 내 작업은 많아야 수십 건이다. 전부 가져와도 비용 차이가 없다
  - 조회와 캐시가 하나로 유지된다. 필터된 목록과 실제 스프린트 내용이 어긋나서 생기는
    버그 부류가 사라진다 (V7의 주의 사항 참고)
- 상태는 이름이 아니라 **`statusCategory`로 판단한다.** 상태 이름은 사이트 언어와 워크플로마다
  다르지만 카테고리(`To Do`/`In Progress`/`Done`)는 Jira가 보장한다. 워크플로에 `Done` 계열
  상태가 여럿이어도(완료/취소 등) 한 번에 묶인다. 커밋 `2e28e89`에서 스프린트 필드를
  번역된 이름 대신 타입으로 찾도록 고친 것과 같은 이유다.
  화면 정렬(V6)과 전환 여부 판단(V6)이 이 값을 쓴다
- 결과는 캐시한다. 커밋 창이 열릴 때마다 네트워크를 치면 안 된다.
  TTL은 짧게(수십 초) 두고 수동 새로 고침을 제공한다
- **호출은 전부 백그라운드 스레드.** EDT에서 부르면 IDE가 멈춘다
  (`CommitIssueKey`가 이미 지키는 규칙과 같다)

**엣지 케이스 테스트**: 열린 스프린트가 없음, 내 작업이 0건, 내 작업이 전부 끝남,
`Done` 카테고리의 상태 이름이 "완료"가 아닌 프로젝트, 상태 카테고리가 응답에 없는 이슈,
스프린트 필드가 없는 프로젝트, JQL 권한 오류(403), 네트워크 실패, 응답 페이지네이션, 캐시 만료.

**완료 기준**: `JiraClientTest`가 가짜 transport로 위 경우를 전부 덮는다.

**완료** (`cf015d6`). 검색은 Jira Cloud의 `/rest/api/3/search/jql`(토큰 페이지네이션)을 쓴다.
스프린트 소속은 필드를 읽지 않고 `key = X AND sprint IN openSprints()` JQL로 되묻는다 —
스프린트 커스텀 필드 ID가 사이트마다 달라서다. 그 되묻기가 실패하면 `true`로 본다(가드가 거짓 경고를
내지 않게).

### V6. Task Start를 "검색·선택"으로 바꾸기 (Version2 #1, #3)

**목적**: 작업 번호를 외워 타이핑하는 대화상자를 없애고, 목록에서 고르게 한다.

- `StartTaskDialog`를 검색 가능한 목록으로 교체 (`SpeedSearch`를 붙인 리스트)
  - V5의 `currentSprintTasks()` 결과를 보여준다:
    `S15P11A108-257  [AUTH-001 | FE] 회원가입 화면 구현`
  - 로딩 중·비어 있음·오류 상태를 각각 보여준다

**목록 정렬** — 상태 카테고리로 묶어 이 순서로 놓는다.

| 순서 | 그룹 | 왜 여기인가 |
| --- | --- | --- |
| 1 | `해야 할 일` | 이 화면을 여는 이유의 대부분이다. 새 작업을 시작한다 |
| 2 | `진행 중` | 이어서 하는 경우(다음 날, 새 IDE 세션)에만 필요하다. 하나씩 끝내므로 보통 1건이다 |
| 3 | `완료` | 후속 커밋용. 흐리게 표시하고 맨 아래에 둔다. 접어 두어도 좋다 |

작업은 하나를 끝내고 다음으로 넘어간다(RULE.md 5.1). 여러 작업을 오가며 하지 않으므로
`진행 중`이 위에 있을 이유가 없다.

- 고르면 로컬 git 설정(`jira-task.current-task`)에 저장하고 `진행 중`으로 옮긴다 (D2).
  **저장이 먼저다.** 전환이 실패해도 작업 선택은 유효해야 한다 (현재 `StartTaskAction`이 지키는 순서)
- **`완료`인 작업을 고르면 번호만 저장하고 전환하지 않는다.** RULE.md 4.3 "되돌리지 않는다".
  후속 커밋에 키는 붙지만 보드는 흔들리지 않는다. 화면에도 그렇게 안내한다
  ("커밋에만 사용합니다. 상태는 그대로 둡니다")
- **탈출구를 남긴다**: 목록을 못 불러와도 번호를 직접 입력할 수 있어야 한다.
  Jira가 죽었다고 커밋을 못 하면 안 된다

**엣지 케이스 테스트**: 목록 로딩 실패 후 수동 입력, 빈 목록, 선택 취소,
**`완료`인 작업을 고름(번호는 저장되고 전환 호출은 일어나지 않음)**,
이미 `진행 중`인 작업을 다시 고름(`AlreadyThere`로 조용히 통과 — RULE.md 4.3 멱등),
저장은 됐지만 상태 전환이 실패한 경우(경고만, 선택은 유지),
automation이 이미 옮겨 둔 작업을 고름, 세 그룹이 각각 비어 있는 경우의 정렬.

**완료** (`e5427de`). 그룹 순서는 `해야 할 일` → `진행 중` → `완료`로 정했다.
`완료` 작업을 고르면 번호만 저장하고 전환 호출을 하지 않는다.

### V7. Commit Guard (Version2 #4)

**목적**: 커밋 직전에 세 가지를 확인한다. RULE.md 5.2 그대로다.

`JiraTaskCheckinHandlerFactory`의 `beforeCheckin`에서:

| 검사 | 실패 시 |
| --- | --- |
| 메시지에 이슈 키가 있는가 | 경고 + "작업 선택" 액션 |
| 그 키가 실제로 존재하는가 | 경고 + 키 표시 |
| 그 작업이 현재 스프린트에 있는가 | 경고 (RULE.md 3.2 이탈) |

- **막지 않고 경고한다.** 커밋을 거부하면 오프라인·Jira 장애 때 일을 못 한다.
  "그래도 커밋" 선택지를 반드시 준다
- Jira 조회 실패는 경고하지 않는다. 확인할 수 없는 것과 위반한 것은 다르다
- V5 캐시를 쓴다. 커밋마다 네트워크를 치지 않는다

> **주의: 캐시 목록에 없다는 것만으로 "스프린트 밖"이라고 경고하지 않는다.**
> `currentSprintTasks()`는 `assignee = currentUser()`로 좁힌 목록이다. 남의 작업 키로 커밋하는
> 경우(대신 처리, 페어 작업)가 있으므로, 목록에 없으면 `issue(key)`로 한 번 더 확인하고
> 그 결과의 스프린트 소속 여부로 판단한다. 캐시는 **빠른 통과 경로**이지 판단 근거가 아니다.

- `완료`인 작업에 커밋하는 것 자체는 **경고하지 않는다.** 규칙 위반이 아니고,
  automation도 되돌리지 않는다 (RULE.md 4.3). V5가 상태로 거르지 않으므로 캐시에도 그대로 있다

**엣지 케이스 테스트**: 키 없음, 형식은 맞지만 없는 키, 다른 프로젝트의 키, 스프린트 밖 작업,
**현재 스프린트의 `완료`된 작업에 커밋(경고 없음)**, **캐시에 없지만 스프린트에는 있는
남의 작업 키(경고 없음)**, Jira 오프라인, 여러 키가 든 메시지, 사용자가 경고를 무시하고 커밋.

**완료** (`d4ead7e`). 계획과 달라진 점 하나: `beforeCheckin()`이 EDT에서 불리므로 **캐시가 아는
답만** 본다(`SprintTasks.findCached`). 대신 커밋 창이 열릴 때 백그라운드로 캐시를 데운다.
그리고 `TaskAnswer`를 새로 두어 "Jira가 없다고 답함"과 "물어보지 못함"을 갈랐다 — 후자에는 침묵한다.

### V8. Jira Quick Search (Version2 #5)

**목적**: IDE를 떠나지 않고 `PROJ-81`을 찾아 제목·설명·상태를 보고 간단히 고친다.

- 툴 윈도우에 검색 입력 + 결과 패널을 추가한다 (또는 `Search Everywhere` 기여자로 붙인다)
- 이슈 키 직접 입력과 제목 부분 일치 검색을 모두 받는다
- **여기서는 `완료`도 찾는다.** V5의 `currentSprintTasks()` 필터를 재사용하지 않는다.
  끝난 작업의 내용을 다시 보는 것이 검색의 주된 쓰임이다.
  스프린트·담당자로도 좁히지 않는다 — 남의 작업도 찾을 수 있어야 한다
- 표시: 키, 제목, 상태, 담당자, 스프린트, 설명
- **수정은 제목·설명만** 허용한다. RULE.md 3.2가 허용한 범위이고, 상태·스프린트는 automation과
  계획의 영역이다. 저장은 `PUT /issue/{key}`, 설명은 ADF(`AdfDocument`)로 보낸다
- 삭제 버튼을 만들지 않는다 (RULE.md 6.2)

**엣지 케이스 테스트**: 없는 키, 권한 없는 이슈, 빈 검색어, `완료`된 이슈가 결과에 나옴,
지난 스프린트의 이슈가 결과에 나옴, 편집 중 충돌(409), 저장 실패 시 입력값 보존,
설명이 ADF가 아닌 옛 이슈.

**완료** (`b30b807`). 툴 윈도우에 붙이는 대신 **별도 다이얼로그 + 액션**(`JiraTask.Search`)으로
만들었다. 툴 윈도우는 읽기 전용 상태 화면이고 검색·편집은 성격이 달라서, 거기에 끼워 넣으면
두 화면이 서로를 방해한다.

설명을 편집 가능한 글로 보여 주려면 ADF를 되돌려야 해서 `AdfDocument.textOf()`를 새로 만들었다.
플러그인이 쓰는 형식(`## 제목` / `[] 항목`)은 왕복하고, 모르는 노드는 글자만 건져 남긴다.

### V9. Smart MR — GitLab (Version2 #2)

**목적**: 현재 브랜치에서 MR을 만들고 작업 이슈를 연결한다.

- 브랜치의 커밋 메시지에서 이슈 키를 전부 추출한다 (`git log <base>..HEAD`)
- 대상 브랜치와 base 브랜치를 고르는 대화상자.
  추출한 이슈 키 목록을 보여주고 조정하게 한다
- 제목·본문은 **규칙 기반**으로 만든다. AI는 다음 단계다
  - 제목: 이슈가 하나면 `KEY 이슈 제목`, 여럿이면 브랜치 이름 기반
  - 본문: 이슈 키 목록 + 링크, 커밋 목록
- `POST /projects/{id}/merge_requests`로 생성한다
- **인증**: GitLab 토큰이 필요하다. Jira 토큰과 같은 방식(PasswordSafe)으로 저장하고,
  설정 화면에 GitLab 주소·토큰 항목을 추가한다
- 프로젝트 ID는 git remote URL에서 유도한다

**엣지 케이스 테스트**: remote가 GitLab이 아님, remote 없음, 푸시하지 않은 브랜치,
이미 열린 MR이 있음, 이슈 키가 하나도 없음, 토큰 없음·만료, base 브랜치와 동일, API 4xx/5xx.

**완료 기준**: 가짜 HTTP transport로 위 경우를 덮고, 실제 생성은 테스트 프로젝트에서만 확인한다.

### V10. Jira Key Navigation (Version2 #6)

**목적**: 코드·커밋 로그·주석의 `PROJ-81`을 눌러 Jira로 이동한다.

- **가장 싼 방법을 먼저 검토한다.** IntelliJ에는 이미
  `Settings | Version Control | Issue Navigation`이 있다. 설정 마법사가 Jira 주소·프로젝트 키로
  **이 항목을 자동 등록**해 주는 것만으로 충분할 수 있다
- 그것으로 부족한 곳(코드·주석)만 링크 제공자를 직접 붙인다
- 패턴은 설정된 프로젝트 키에만 반응한다. 아무 대문자-숫자 조합에 반응하면 오탐이 심하다

**엣지 케이스 테스트**: 프로젝트 키 미설정, 여러 프로젝트 키, 문자열 리터럴 안의 키,
키처럼 보이지만 아닌 것(`UTF-8`, `HTTP-2`).

### V11. 문서 정리

- `README.md`: 기능 표를 Version2 기준으로 다시 쓴다. 이슈 생성·검증 문단 삭제
- `ARCHITECTURE.md`: 1장 목표·범위·비범위를 RULE.md 0장·6장과 일치시킨다.
  `domain/validation`, `domain/template`, `domain/input`이 사라진 구조도를 반영한다
- `MIGRATION-LOG.md`: V1~V10에서 내린 결정을 이어서 기록한다
- `Version2.md`: 반영이 끝나면 지운다. 결정 내용은 RULE.md와 이 문서에 남는다

---

## 5. 순서와 의존

```
V1 (이슈 생성·검증 삭제) ─┬─ V2 (설정 축소)
                          ├─ V3 (완료 전환 제거) ─┐
                          └─ V4 (브랜치 생성 삭제) ┴─ 설정 마법사 단계 정리

V5 (SprintTaskReader) ─┬─ V6 (Task 선택)
                       ├─ V7 (Commit Guard)
                       └─ V8 (Quick Search)

V9  (Smart MR)    — 독립. GitLab 인증 설정이 선행
V10 (Navigation)  — 독립. 가장 작다. 먼저 해도 된다
V11 (문서)        — 마지막
```

**V1~V4를 먼저 끝낸다.** 지울 것을 지우기 전에 새 기능을 얹으면, 사라질 코드에 맞춰 새 코드를 쓰게 된다.
V5는 V6~V8의 공통 기반이므로 그다음이다.

---

## 6. 이번 범위에서 하지 않는 것

| 항목 | 이유 |
| --- | --- |
| AI 커밋 제목·MR 본문 생성 | 규칙 기반으로 먼저 완성한다. AI는 다음 단계 |
| AI Task 추천, AI commit 분할, AI Jira 검색 | Claude + MCP 같은 범용 에이전트가 더 잘한다 (Version2.md) |
| GitHub PR 지원 | 대상은 GitLab MR이다 |
| 브랜치 생성·이름 형식 강제 | RULE.md 5.1. 브랜치 이름은 내용 요약이면 되고 규칙이 없다 |
| 이슈 생성·삭제 기능의 부활 | RULE.md 전제 A·B |
| 스프린트 생성·이슈 이동·일괄 수정 | RULE.md 전제 B |
| Jira automation 규칙을 코드로 복제 | RULE.md 4.3. 정본은 Jira에 있다 |
