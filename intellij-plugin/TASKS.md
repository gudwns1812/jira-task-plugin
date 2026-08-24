# 툴 윈도우·설정 UI 개선 작업 목록

CLI → 플러그인 이식(T1~T26)은 끝났고 기능은 동작한다. 남은 문제는 **UI 품질**이다. 특히 T26에서 넣은
오른쪽 사이드바 툴 윈도우가 IntelliJ 툴 윈도우 관례를 따르지 않아, 툴 윈도우가 아니라 설정 폼처럼 보인다.
이 문서는 그것을 다시 만드는 작업 분해서다. 이식 작업의 기록과 결정 사항은 [MIGRATION-LOG.md](MIGRATION-LOG.md)에 있다.

모든 작업은 `AGENTS.md`의 테스트 우선 완료 기준(정상 동작 테스트 + 엣지 케이스 테스트 + 전체 테스트 통과)을 따르고,
MIGRATION-LOG.md에 정리한 코틀린 코드 스타일 규칙(companion 팩토리 / 멤버 함수 / 이름 있는 object / 생성자 주입 클래스)도 그대로 지킨다.

---

## 0. 지금 무엇이 문제인가

`ui/toolwindow/JiraTaskToolWindow.kt`, `ui/JiraTaskConfigurable.kt` 기준.

| # | 문제 | 왜 문제인가 |
| --- | --- | --- |
| P1 | Kotlin UI DSL `panel { }`에 `button()` 6개를 나열했다 | 툴 윈도우는 아이콘 툴바로 조작하는 것이 관례다. 지금은 설정 대화상자처럼 보이고, 폭을 좁히면 버튼이 잘린다 |
| P2 | 제목 표시줄 액션이 없다 | 새로 고침·설정 열기는 툴 윈도우 제목 표시줄(gear/refresh)에 있어야 한다 |
| P3 | 액션을 `AnActionEvent.createFromDataContext`로 직접 호출한다 | 각 액션의 `update()`(활성/비활성 판단)가 실행되지 않는다. 설정이 없어도 버튼이 눌리고, 실패 알림으로만 알려준다 |
| P4 | 미설정 상태를 라벨 문구 한 줄로 안내한다 | 처음 쓰는 사용자가 무엇을 해야 하는지 알기 어렵다. 빈 상태에는 실행 가능한 링크가 있어야 한다 |
| P5 | 값이 그냥 `JBLabel` 텍스트다 | 복사할 수 없고, "설정되지 않음" 같은 자리 문구와 실제 값이 시각적으로 구분되지 않으며, Jira 주소가 링크가 아니다 |
| P6 | 갱신이 "새로 고침" 버튼 수동 호출뿐이다 | 브랜치를 바꾸거나 작업 번호를 저장해도 패널이 낡은 값을 보여준다 |
| P7 | 문구가 코드에 하드코딩되어 있다 | 플랫폼 관례(메시지 번들, `@Nls`)에서 벗어나고, 문구를 한곳에서 다듬을 수 없다 |
| P8 | 툴 윈도우 아이콘으로 `AllIcons.Vcs.Branch`를 빌려 쓴다 | 플러그인 고유 아이콘이 없어 사이드바에서 식별되지 않는다 |
| P9 | Settings 패널도 라벨 나열이다 | 그룹 구분·복사·링크·인라인 검증이 없어 정보가 읽기 어렵다 |

## 1. 지향점

- **툴바로 조작한다**: `SimpleToolWindowPanel` + `ActionToolbar`. 버튼이 곧 액션이므로 액션의 `update()`가 활성/비활성과 툴팁을 결정한다(P1·P3 동시 해결).
- **상태는 읽기 화면이다**: 값은 복사 가능하고, 없는 값은 회색 자리 문구로, Jira 주소는 클릭하면 브라우저로 연다.
- **처음 쓰는 사람에게 다음 행동을 준다**: 설정이 없으면 상태 행을 늘어놓지 않고 빈 상태 + "설정 마법사 실행" / "설정 열기" 링크만 보여준다.
- **스스로 갱신한다**: 툴 윈도우가 보일 때, 액션이 성공했을 때 자동으로 다시 읽는다.
- **EDT를 막지 않는다**: git 조회는 백그라운드에서, 화면 반영만 EDT에서. (T26에서 지킨 규칙을 계속 유지한다)

---

## 2. 작업

### W1. 문구를 메시지 번들로 옮기기
- **내용**: `messages/JiraTaskBundle.properties` + `JiraTaskBundle` 오브젝트(`DynamicBundle` 상속)를 만들고, 툴 윈도우·설정 패널·알림·다이얼로그 문구를 옮긴다. 반환 문구에 `@Nls`를 붙인다.
- **완료 기준**: 기존 테스트가 문구를 문자열 리터럴로 비교하던 부분을 번들 조회로 바꿔도 전부 통과. 번들에 없는 키를 조회하면 실패하는 테스트 1건 추가.
- **주의**: 도메인·애플리케이션 계층의 검증 메시지는 CLI와 문구가 같아야 하므로 **옮기지 않는다**(플랫폼 번들은 UI 계층 문구만).

### W2. 툴 윈도우 골격을 `SimpleToolWindowPanel` + 툴바로 교체 — (완료)
- **내용**: `JiraTaskToolWindowPanel`을 `SimpleToolWindowPanel(vertical = true, borderless = true)` 기반으로 다시 만든다. 상단에 `ActionManager.createActionToolbar("JiraTaskToolWindow", group, true)`, 본문은 `JBScrollPane`. `plugin.xml`에 툴바용 액션 그룹 `JiraTask.ToolWindowToolbar`를 선언하고 기존 액션 4개(이슈 초안·브랜치·작업 시작·커밋)를 넣는다. `button()` 6개는 제거한다.
- **의존**: 없음(W1과 독립).
- **완료 기준**: 툴바 액션 그룹의 자식 액션 ID가 기대 순서와 같은지, 패널에 `JButton`이 더 이상 없는지, 툴바 `targetComponent`가 설정되어 있는지(미설정 시 플랫폼 경고 발생) 테스트.
- **산출물**: `ui/toolwindow/JiraTaskToolWindow.kt`(패널이 `SimpleToolWindowPanel(true, true)`를 상속해 자체가 컴포넌트가 됨 — 툴바는 `toolbar`, 본문은 `setContent(JBScrollPane(...))`), `plugin.xml`에 `JiraTask.ToolWindowToolbar` 그룹.
- **결정 1 — 액션 아이콘을 W3에서 앞당겼다**: 아이콘 없는 액션은 툴바에서 빈 칸으로 보여 툴바 자체가 못 쓰는 상태가 된다. 그래서 액션 5개에 `AllIcons`(Add / Vcs.Branch / Actions.Execute / Actions.Commit / General.Settings)를 이번에 지정했다. 활성/비활성 판단과 비활성 이유 툴팁은 W3에 그대로 남는다.
- **결정 2 — 툴바가 메뉴와 같은 액션을 참조한다**: `Tools > Jira Task` 메뉴에서 선언한 액션을 `<reference>`로 재사용해 두 경로의 동작이 갈라지지 않게 했다.
- **결정 3 — 새로 고침은 잠시 툴바에 둔다**: 버튼 6개를 없애면 새로 고침 수단이 사라지므로, 패널이 자체 `RefreshStatusAction`(등록된 ID 없는 로컬 액션)을 툴바 끝에 덧붙였다. W6에서 제목 표시줄로 옮긴다.
- **테스트**: `ui/toolwindow/JiraTaskToolWindowTest.kt` — 폼 버튼이 하나도 없는지, 툴바가 하나이고 `place`·`targetComponent`가 설정됐는지, 툴바 그룹의 자식 액션 ID가 기대 순서와 같은지, 모든 툴바 액션에 아이콘이 있는지(스텁이 아니라 `ActionManager.getAction`으로 실제 액션의 프레젠테이션 확인), 패널이 새로 고침을 덧붙이는지. 총 265개 테스트 통과, `build`·`buildPlugin`·`verifyPluginStructure` 통과.
- **테스트 작성 중 알게 된 것**: `ActionToolbar.getActions()`는 툴바가 비동기로 갱신되기 전까지 비어 있고, 그룹의 `childActionsOrStubs`는 아직 인스턴스화되지 않은 `ActionStub`이라 아이콘이 null이다. 그래서 아이콘 검증은 ID로 실제 액션을 얻어서 한다.

### W3. 액션에 활성/비활성 판단 넣기 — (완료)
- **내용**: `CreateIssueDraftAction`·`CreateStoryBranchAction`·`StartTaskAction`·`CommitWithJiraTaskAction`의 `update()`가 `JiraTaskSettings` 결과까지 보고 `isEnabled`를 정하고, 비활성일 때 `presentation.description`에 이유를 넣는다(툴팁으로 보인다). 각 액션에 아이콘(`AllIcons.*`)을 지정한다. 툴바가 액션을 직접 실행하므로 `performAction`/`createFromDataContext` 경로는 삭제한다.
- **의존**: W2.
- **완료 기준**: 설정 없음 / 프로젝트 키만 있음 / 전부 있음 세 경우에 대해 액션 4개의 `isEnabled`와 툴팁 문구를 검증(`TestActionEvent`). 설정이 없을 때 액션을 실행해도 예외가 나지 않고 안내 알림만 뜨는 기존 동작은 유지(툴바 밖 메뉴에서 실행될 수 있으므로).
- **주의**: `update()`는 BGT에서 돈다(`ActionUpdateThread.BGT`). 설정 조회는 인메모리라 괜찮지만 **git 호출은 넣지 않는다**.
- **설계**: 판단 규칙을 액션마다 흩지 않고 `ui/JiraTaskAvailability.kt`(`JiraTaskCommand` + `ActionAvailability`)로 모았다. 액션은 `event.applyAvailability(availability.of(command, hasProject))` 한 줄만 부른다. 쓸 수 있을 때는 `presentation.description`을 덮지 않아 plugin.xml의 설명이 툴팁으로 남고, 못 쓸 때만 이유로 바뀐다.
- **명령별 규칙**: `START_TASK`는 Jira 설정 없이도 되는 git 전용 동작이라 프로젝트만 있으면 활성. `ISSUE_DRAFT`·`COMMIT`은 프로젝트 키 필요. `STORY_BRANCH`는 프로젝트 키·Jira 주소·스토리 유형·스토리 시작 상태가 필요하고, 없는 항목만 골라 이유에 적는다.
- **결정 — update()에서 자격 증명 저장소를 읽지 않는다**: `JiraTaskSettings.forBranch()`는 API 토큰을 PasswordSafe에서 읽는데, `update()`는 툴바가 보이는 동안 반복 호출되므로 OS 키체인 조회를 넣으면 IDE가 느려진다. 그래서 토큰과 Git 저장소 여부는 활성 판단에서 빼고 실행 시 확인해 알림으로 안내한다(비활성 이유에 토큰이 등장하지 않는 것을 테스트로 고정).
- **산출물**: `ui/JiraTaskAvailability.kt`, 액션 4개의 `update()`(+`CreateIssueDraftAction`은 `update()` 자체가 없었으므로 신규), 각 액션에 `JiraTaskAvailability` 생성자 주입.
- **테스트**: `ui/JiraTaskAvailabilityTest.kt` (9 tests — 프로젝트 없음, 설정 완료, 작업 시작만 예외, 프로젝트 키 요구, 브랜치 미충족 항목 나열, 이미 있는 설정은 언급 안 함, 토큰 없이도 활성, 손상된 설정, 저장 후 즉시 반영), `ui/JiraTaskActionUpdateTest.kt` (6 tests — 액션 4개의 실제 `update()` 결과와 툴팁 문구, 쓸 수 있을 때 원래 설명 유지, 비활성 상태에서 실행해도 예외 없이 안내). `CreateStoryBranchDialogTest`의 옛 활성화 테스트는 규칙이 바뀌어 새 규칙을 쓰는지 확인하는 형태로 교체했다. 총 280개 통과.

### W4. 상태 표시를 제대로 만들기 — (완료)
- **내용**: 값 라벨을 `JBLabel().apply { setCopyable(true) }`로, 없는 값은 `NamedColorUtil.getInactiveTextColor()` 회색 자리 문구로, Jira 주소는 `ActionLink` + `BrowserUtil.browse`로, 이슈 키·브랜치는 모노스페이스 폰트로. 행 라벨과 값의 정렬·여백을 `panel { row(...) }` 기본 간격에 맞춘다.
- **의존**: W2.
- **완료 기준**: 설정된 상태에서 값이 복사 가능(`isCopyable`)하고, 미설정 값은 회색이며, Jira 주소 컴포넌트가 `ActionLink`이고 그 링크 대상이 저장된 baseUrl과 같은지 테스트. 이메일·토큰이 어떤 컴포넌트 텍스트에도 없는지 확인하는 기존 성격의 테스트 유지.
- **설계**: 표시 규칙을 순수 모델까지 끌어올렸다. `StatusRow`가 이름·문구와 함께 `isPlaceholder`(실제 값인지)와 `kind`(TEXT / LINK / CODE)를 들고 있고, `StatusRowView`가 종류별 컴포넌트를 만든다 — TEXT·CODE는 복사 가능한 `JBLabel`, LINK는 `ActionLink`. 항목의 종류는 값이 있든 없든 고정이라 컴포넌트를 한 번만 만들고 값만 갈아 끼운다.
- **미설정 값 처리**: 자리 문구는 `NamedColorUtil.getInactiveTextColor()` 회색으로, 링크는 열 곳이 없으면 `isEnabled = false`로 두어 클릭되지 않는다. 고정폭 폰트는 실제 값(프로젝트 키·작업 번호·브랜치)에만 쓰고 안내 문구는 기본 폰트로 둔다.
- **브라우저 열기**: 패널이 `browse: (String) -> Unit`을 생성자로 받아 기본값은 `BrowserUtil.browse`, 테스트에서는 호출을 기록한다.
- **산출물**: `ui/toolwindow/StatusRowView.kt`, `JiraTaskStatus`에 `StatusRow`·`statusRows()` 추가(`rows()`는 이름·문구 축약형으로 유지), 패널이 `rowViews`로 값을 갱신.
- **테스트**: `JiraTaskStatusTest`에 3건(자리 문구 표시, 종류 지정, 값이 없어도 종류 유지), `JiraTaskToolWindowTest`에 5건(복사 가능·회색 자리 문구, 고정폭 폰트 적용 범위, 링크 클릭이 저장된 주소를 여는지, 주소가 없으면 클릭되지 않는지, 이메일 미노출). 총 288개 통과.
- **플랫폼 동작 기록 2건**: (1) `JBLabel`에는 `isCopyable` 게터가 없어 `setCopyable(true)`가 붙이는 텍스트 컴포넌트 자식으로 확인한다. (2) `ActionLink`는 `JButton`을 상속하므로 "폼 버튼이 없어야 한다" 검사에서 제외해야 한다.

### W5. 미설정·손상 상태를 빈 상태로 보여주기 — (완료)
- **내용**: 프로젝트 키가 없으면 상태 행 대신 `JBPanelWithEmptyText`의 `emptyText`에 안내 + `설정 마법사 실행`·`설정 열기` 링크(`SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES` + 클릭 리스너)를 보여준다. 설정이 손상된 경우에는 경고 아이콘과 오류 문구, `설정 열기` 링크를 보여준다.
- **의존**: W2, W4. `JiraTaskStatus.isConfigured`/`problem`은 이미 있으므로 그대로 쓴다.
- **완료 기준**: 미설정 / 손상 / 정상 세 상태에서 보이는 컴포넌트가 각각 빈 상태·오류·상태 행인지, 링크 클릭이 마법사·설정 화면 호출로 이어지는지(호출을 람다로 주입해 검증) 테스트.
- **설계**: 본문을 세 화면(상태 행 / 설정 안내 / 오류)으로 나누고 한 번에 하나만 보이게 한다. 어느 화면인지는 순수 모델이 정한다 — `StatusProblem(kind, message)`를 도입해 `NEEDS_SETUP`과 `BROKEN`을 구분하고(이전에는 문구 하나뿐이라 화면을 문구 비교로 골라야 했다), 패널은 `problem?.kind`만 보고 가시성을 정한다. 어떤 화면인지 테스트에서 볼 수 있게 `shown()`을 공개했다.
- **결정 — `JBPanelWithEmptyText`의 `emptyText` 대신 실제 컴포넌트로 빈 상태를 만들었다**: 계획은 `emptyText`에 링크를 넣는 것이었지만, (1) `StatusText`의 링크는 클릭을 테스트로 확인할 마땅한 공개 API가 없고, (2) 경고 아이콘을 함께 보여주기 어렵다. 아이콘 + 안내 문구 + `ActionLink` 두 개를 Kotlin UI DSL로 구성해 관례를 지키면서 클릭까지 검증한다.
- **결정 — 미설정 상태에서는 git 정보도 감춘다**: "설정되지 않음" 일곱 줄 대신 할 일을 보여주는 것이 이 화면의 목적이고, 브랜치·작업 번호는 상태 표시줄 위젯에서 계속 볼 수 있다. 손상된 설정일 때는 오류 문구와 설정 열기 링크를 보여준다.
- **링크 동작**: `launchWizard`·`openSettings`를 생성자로 주입(기본값은 `SetupWizardLauncher.launch`와 `ShowSettingsUtil.showSettingsDialog(project, JiraTaskConfigurable::class.java)`)해 테스트가 실제 마법사·설정 창을 열지 않고 호출만 센다.
- **테스트**: 미설정에서 안내 화면과 두 링크가 보이고 "설정되지 않음" 행은 보이지 않는지, 링크 클릭이 마법사·설정 호출로 이어지는지, 손상된 설정에서 오류 문구와 설정 열기 링크가 나오는지, 설정을 마치면 상태 행으로 돌아오는지. `JiraTaskStatusTest`의 문구 검증은 `problem?.message`·`problem?.kind`로 갱신. 총 291개 통과.
- **주의(테스트 작성 중 발견)**: 생성자에 람다 파라미터를 더하면 기존 테스트의 후행 람다(`Panel(project) { ... }`)가 마지막 파라미터에 붙어 조용히 다른 인자로 해석된다. W4의 `browse` 인자를 이름 있는 인자로 바꿔 고쳤다.

### W6. 제목 표시줄 액션과 자동 갱신
- **내용**: `toolWindow.setTitleActions(listOf(새로 고침, 설정 열기))` — 새로 고침은 `AllIcons.Actions.Refresh`, 설정 열기는 `AllIcons.General.Settings` + `ShowSettingsUtil.showSettingsDialog(project, JiraTaskConfigurable::class.java)`. 자동 갱신은 (1) `ToolWindowManagerListener`로 툴 윈도우가 보일 때, (2) 작업 번호 저장·브랜치 생성·커밋 성공 시 자체 메시지 버스 토픽(`JiraTaskStateListener`)을 발행해 구독.
- **의존**: W2.
- **완료 기준**: 토픽을 발행하면 패널이 다시 읽는지, 툴 윈도우 표시 이벤트에 반응하는지 테스트. 새로 고침이 EDT에서 git을 호출하지 않는 것을 확인(백그라운드 호출 여부를 스레드 이름으로 검증하거나, 읽기 함수를 주입해 호출 스레드를 기록).
- **검토 항목**: Git 브랜치 변경 자동 감지는 `git4idea`의 `GitRepositoryChangeListener`가 필요하다. MIGRATION-LOG.md의 T13 결정(Git4Idea 의존 추가 보류)과 충돌하므로, 이 작업에서는 **툴 윈도우 표시 시 갱신으로 대신하고** git4idea 의존은 별도 작업으로 판단한다.

### W7. 플러그인·툴 윈도우 아이콘
- **내용**: 13x13 / 16x16 SVG 아이콘을 만들어 `icons/`에 넣고 `IconLoader.getIcon`으로 상수화, `plugin.xml`의 `toolWindow icon`을 그것으로 바꾼다. 다크 테마용 `_dark.svg`도 함께 둔다.
- **완료 기준**: 아이콘 리소스가 실제로 로드되는지(`IconLoader`가 빈 아이콘을 돌려주지 않는지) 테스트, `buildPlugin` 통과.

### W8. Settings 패널 다듬기
- **내용**: `JiraTaskConfigurable`을 그룹(연결 / 이슈 유형·상태 / 업무 정책)으로 나누고, 값은 복사 가능하게, Jira 주소는 링크로, "설정 마법사 다시 실행"은 상단 링크 형태로 옮긴다. 프로젝트 키·주소 입력 오류는 Apply 후 대화상자 대신 필드 옆 인라인 검증(`Cell.validationOnInput`/`validationOnApply`)으로 보여준다.
- **의존**: W1, W4(같은 표시 규칙 재사용).
- **완료 기준**: 잘못된 프로젝트 키를 넣으면 인라인 검증 메시지가 나오고 저장되지 않는지, 정상 값은 저장되는지 테스트. 이메일·토큰 미노출 테스트 유지.

### W9. 수동 확인과 기록
- **내용**: `./gradlew runIde`로 다음을 눈으로 확인하고 결과를 이 문서에 남긴다.
  - 사이드바 폭을 최소로 줄였을 때 라벨·값이 잘리지 않는지(잘리면 두 줄 배치 또는 툴팁으로 보완)
  - 라이트/다크 테마에서 회색 자리 문구와 링크 색이 모두 읽히는지
  - 한글 문구가 툴바 툴팁·빈 상태에서 깨지지 않는지
  - 툴바 아이콘만으로 각 액션이 무엇인지 알 수 있는지(모르겠으면 아이콘 교체)
- **완료 기준**: 확인 결과와 고친 내용을 이 문서 아래 "수동 확인 기록"에 추가.

---

## 3. 하지 않을 것 (이번 범위 밖)

- **실제 Jira 이슈 생성(POST)**: CLI에도 없는 기능이라 이식 대상이 아니다. 필요하면 별도 작업으로 설계부터 시작한다.
- **git4idea 의존 추가**: 브랜치 변경 실시간 감지에만 필요하다. W6의 검토 항목으로 남긴다.
- **CLI 설정 가져오기**: `~/.jira-task/policy.json`을 읽어오는 기능은 MIGRATION-LOG.md T12에서 별도 작업으로 미뤘다.
- **툴 윈도우에 이슈 목록·검색**: Jira 조회 API를 더 쓰는 새 기능이다. 지금은 "현재 상태 + 실행"에 집중한다.

## 4. 권장 순서

W2 → W3 → W4 → W5 → W6 → W1 → W7 → W8 → W9.

골격(W2)과 액션 활성화(W3)가 먼저 바뀌어야 나머지 표시 작업의 기준이 생긴다. 문구 번들(W1)은 화면 구성이
확정된 뒤에 옮기는 편이 중복 수정이 적다.

## 5. 수동 확인 기록

(W9에서 채운다)
