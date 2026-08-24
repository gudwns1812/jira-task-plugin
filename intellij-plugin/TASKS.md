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

### W6. 제목 표시줄 액션과 자동 갱신 — (완료)
- **내용**: `toolWindow.setTitleActions(listOf(새로 고침, 설정 열기))` — 새로 고침은 `AllIcons.Actions.Refresh`, 설정 열기는 `AllIcons.General.Settings` + `ShowSettingsUtil.showSettingsDialog(project, JiraTaskConfigurable::class.java)`. 자동 갱신은 (1) `ToolWindowManagerListener`로 툴 윈도우가 보일 때, (2) 작업 번호 저장·브랜치 생성·커밋 성공 시 자체 메시지 버스 토픽(`JiraTaskStateListener`)을 발행해 구독.
- **의존**: W2.
- **완료 기준**: 토픽을 발행하면 패널이 다시 읽는지, 툴 윈도우 표시 이벤트에 반응하는지 테스트. 새로 고침이 EDT에서 git을 호출하지 않는 것을 확인(백그라운드 호출 여부를 스레드 이름으로 검증하거나, 읽기 함수를 주입해 호출 스레드를 기록).
- **검토 항목**: Git 브랜치 변경 자동 감지는 `git4idea`의 `GitRepositoryChangeListener`가 필요하다. MIGRATION-LOG.md의 T13 결정(Git4Idea 의존 추가 보류)과 충돌하므로, 이 작업에서는 **툴 윈도우 표시 시 갱신으로 대신하고** git4idea 의존은 별도 작업으로 판단한다.
- **구현**: 제목 표시줄에 새로 고침(`AllIcons.Actions.Refresh`)과 설정 열기(`AllIcons.General.Settings` → `ShowSettingsUtil.showSettingsDialog(project, JiraTaskConfigurable::class.java)`)를 `toolWindow.setTitleActions`로 달고, 툴바에서 새로 고침을 뺐다(툴바는 실행 액션만).
- **자동 갱신 경로 두 가지**: (1) `JiraTaskStateListener`(프로젝트 레벨 토픽) — 작업 번호 저장·브랜치 생성·커밋·설정 마법사 완료·Settings 저장이 알린다. (2) `ToolWindowManagerListener.toolWindowShown` — 다른 창에서 브랜치를 바꾸고 툴 윈도우로 돌아온 경우를 잡는다(git4idea 없이 브랜치 변경을 따라가는 현실적인 대안).
- **알림과 갱신을 묶었다**: `notifyAndRefresh(project, type, message)` 하나로 결과 알림과 상태 변경 통지를 함께 처리해, 알림만 띄우고 화면 갱신을 잊는 실수를 막는다. Settings 패널은 프로젝트가 없으므로 `publishToAllProjects()`로 열린 프로젝트 전부에 알린다.
- **테스트 가능성**: 상태를 읽는 함수(`statusReader`)를 생성자로 주입할 수 있게 해, 갱신 횟수와 **호출 스레드**를 기록한다. 그래서 "git을 EDT에서 읽지 않는다"를 테스트로 고정할 수 있다.
- **산출물**: `ui/JiraTaskStateListener.kt`(토픽 + `notifyAndRefresh`), 패널의 `titleActions()`·`onToolWindowShown()`·구독, 액션 3개와 마법사·Settings의 통지 호출, 팩토리에서 `setTitleActions` 연결.
- **테스트**: `ui/JiraTaskStateTest.kt` (4 tests — 통지 전달, 알림과 통지 동시 처리, 프로젝트가 없을 때, 애플리케이션 레벨 통지), `JiraTaskToolWindowTest`에 5건(툴바에는 실행 액션만, 제목 표시줄 액션 2개와 각 동작, EDT에서 git을 읽지 않음, 상태 변경 통지 시 갱신, 이 툴 윈도우가 보일 때만 갱신). 총 299개 통과.

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

### W10. 작업 이슈 입력을 간단 모드로 줄이기 — (완료)
- **문제**: 작업(task) 하나를 만들려면 필드 10개(에픽 키·스토리 키·제목·담당자·우선순위·포인트·스프린트·기한·설명·결과물)를 손으로 채워야 했다. CLI가 JSON으로 받던 것을 폼으로 그대로 옮겨놨을 뿐이라, JSON을 쓰지 않기 위해 만든 플러그인인데도 타이핑량이 줄지 않았다.
- **결정(사용자 확인)**: (1) 간단 모드를 기본으로 하고 자동으로 채운 값은 접힌 "고급"에 둔다. (2) 제목 접두는 Jira를 조회해 자동 생성한다.
- **자동으로 채우는 값**:
  - 스토리 키 ← 현재 브랜치. `feat/100-signup`이면 규칙상 스토리 번호는 100 → `<프로젝트 키>-100` (`BranchNames.storyNumberOf`, `createBranchName`의 역함수로 도메인에 추가)
  - 기능 ID ← 그 스토리의 Jira 제목. `[AUTH-001] 이메일 회원가입` → `AUTH-001` (기존 `TitleParser.parseStoryTitle` 재사용, `JiraIssue`에 `summary` 추가)
  - 담당자·우선순위·스프린트·기한·에픽 키·영역·포인트 ← 프로젝트별로 기억한 지난번 입력(`IssueDraftMemory`, 프로젝트 레벨 `PersistentStateComponent`)
  - 포인트·영역 ← 자유 입력 대신 정책 값 콤보박스
- **타이핑량**: 작업 기준 10칸 → **요약 한 줄 + 설명 + 결과물** (구분·영역은 콤보 선택). 제목은 `TaskTitles.compose(구분, 영역, 요약)`가 `[AUTH-001 | FE] 회원가입 폼` 형태로 조립하고 "만들어질 제목"으로 미리 보여준다. 공통 업무는 구분에서 업무 분류(PLAN 등)를 고르고 영역을 `(없음)`으로 두면 `[PLAN] …`이 된다.
- **조회 실패는 막지 않는다**: 브랜치가 규칙에 맞지 않거나 Jira를 못 읽으면 아는 값까지만 채우고 이유를 폼 아래 주석으로만 알린다(검증 오류로 다루지 않는다). 조회는 액션이 `Task.Backgroundable`에서 하고 다이얼로그는 그 뒤에 열린다.
- **산출물**: `domain/branch/BranchName.kt`에 `storyNumberOf`, `domain/jira/StoryIssueReader.kt`의 `JiraIssue.summary`(+`JiraClient`가 `fields=…,summary` 조회), `ui/issue/IssueDraftContext.kt`(`TaskTitles`, `IssueDraftContext`, `RememberedInput`), `ui/issue/IssueDraftContextReader.kt`, `ui/issue/IssueDraftMemory.kt`, `IssueDraftForm` 전면 개편(간단 모드 + `collapsibleGroup("고급")`), `CreateIssueDraftDialog`가 컨텍스트를 받고 확인 시 값을 기억, `CreateIssueDraftAction`이 컨텍스트를 먼저 읽는다.
- **테스트**: `ui/issue/IssueDraftContextTest.kt` (10 tests — 브랜치에서 스토리·기능 ID 유도, 기능 ID를 구분 후보 맨 앞에, 평범한 브랜치면 Jira를 부르지 않음, 조회 실패·연결 없음·기능 ID 없는 제목의 안내, 기억값/정책 기본값, 제목 조립, 스토리 제목만 기능 ID로 인정), `ui/issue/IssueDraftMemoryTest.kt` (6 tests — 빈 값으로 기억을 지우지 않음, 트림, 저장·복원, 프로젝트 서비스), `domain/branch/BranchNameTest.kt`에 2건, `CreateIssueDraftDialogTest` 재작성(11 tests — 자동 채움 확인, 제목 조립·미리보기, 요약과 결과물만으로 유효, 공통 업무 제목, 브랜치가 알려주지 않을 때 업무 분류 후보, 조회 실패 안내가 검증 오류가 아님, 확인 시 기억, 스토리·에픽은 제목 그대로). 총 320개 통과.
- **버그 수정 기록**: 폼이 생성 중에 `onChange`를 부르면 아직 만들어지지 않은 다이얼로그 필드를 건드려 NPE가 났다(`applyType` → `changed()` → 다이얼로그 `refresh()`). 생성 중에는 미리보기만 갱신하고 `onChange`는 사용자 조작에서만 부르도록 분리했다.

### W11. 무엇을 고쳐야 하는지 알려주기 — (완료)
- **문제(사용자 지적 3건)**: (1) 작성 예시가 별도 칸을 차지해 화면만 길었다. (2) 검증 결과가 `오류 - dueDate: 값을 입력해야 합니다.`처럼 JSON 필드 경로였고 색 구분도 없어, 어느 칸이 문제인지 알 수 없었다. (3) 필수인데 비어 있는 값(기한 등)이 접힌 "고급"에 있어 오류는 보이지만 고칠 칸을 찾을 수 없었다.
- **작성 예시 → 자리 문구**: `작성 예시` 칸을 없애고 각 입력의 `emptyText`(placeholder)로 옮겼다 — 제목 요약 `회원가입 폼`, 설명 `작업 설명을 작성합니다.`, 결과물 `완료 기준 결과물 1`, 기한 `2026-08-31`, 에픽 키 `<프로젝트 키>-1` 등. T20 계획의 "설명 필드에 템플릿을 placeholder로" 항목을 이제야 제대로 지켰다.
- **오류 표시**: 검증 결과를 문자열 대신 `IssueProblem(path, message)`로 다루고, `IssueFieldLabels`가 경로를 화면 이름으로 바꾼다(`dueDate` → `기한`, `description.summary` → `설명`, `description.deliverables.1` → `결과물 2번째`). 오류는 `NamedColorUtil.getErrorForeground()` 빨간색 + 오류 아이콘, 경고는 경고 아이콘, 문제가 없으면 도출된 레이블을 함께 보여준다. 도메인·애플리케이션의 메시지는 CLI와 같은 경로 문자열을 그대로 쓰고, 사람이 읽는 이름은 화면에서만 붙인다.
- **접는 기준을 바꿨다**: "고급 설정이냐"가 아니라 **"이미 값이 있느냐"**로 접는다. 비어 있는 필수 칸(에픽 키·담당자·우선순위·스프린트·기한)은 항상 보이고, 기억된 값이 있으면 접힌다. `채워진 값도 모두 보기` 체크박스로 전부 펼칠 수 있고, 검증에서 문제가 난 칸은 접혀 있어도 자동으로 펼친다(`revealProblems`). 접힘 계산은 유형 전환·체크박스에서만 다시 하고 **타이핑 중에는 하지 않는다** — 값을 입력하는 순간 그 줄이 사라지면 안 되니까.
- **기본 유형을 task로**: 대부분 작업을 만들므로 다이얼로그를 열면 바로 작업 폼이 보인다(이전에는 콤보 첫 항목인 epic이 선택돼 있었다).
- **산출물**: `ui/issue/IssueFieldLabels.kt`, `IssueDraftPresenter`의 `IssueProblem`·`problemPaths()`, `IssueDraftForm` 재구성(자리 문구, 값 유무 기반 접힘, `showAllCheckBox`, `visiblePaths()`), `CreateIssueDraftDialog`의 색이 있는 검증 결과 패널.
- **테스트**: `IssueDraftPresenterTest`에 3건(칸 이름으로 보여주기, 결과물 번호, 문제 있는 경로 목록), `CreateIssueDraftDialogTest`에 5건(자리 문구, 채워진 값은 접기, 비어 있는 필수 칸은 유지, 모두 보기, 오류가 빨간색이고 칸 이름을 쓰는지, 문제 없을 때 안내). 총 328개 통과.
- **플랫폼 동작 기록**: Kotlin UI DSL의 `Row.visible()`은 setter만 있어 현재 가시성을 되읽을 수 없다 — 지금 보이는 칸을 폼이 따로 기억한다.

### W12. 물어보지 말고 Jira에서 알아내기 — (완료)
- **문제(사용자 지적 4건)**: (1) 작업을 만드는데 에픽 키를 왜 입력하나. (2) 담당자를 자기 자신으로 기본 지정 못 하나. (3) 우선순위는 자유 입력이 아니라 선택이어야 하고 기본값은 낮은 쪽. (4) 스프린트는 스토리에 딸린 값인데 왜 입력하나. 기한은 달력으로.
- **답**: 모두 사람이 입력할 값이 아니다. 작업은 스토리에 속하므로 **에픽과 스프린트를 스토리에서 물려받고**, 담당자는 **지금 연결된 Jira 사용자**, 우선순위는 **Jira에 정의된 목록에서 선택**, 기한은 **달력**에서 고른다.
- **새 Jira 조회(`domain/jira/JiraDraftReader.kt` 포트, `JiraClient` 구현)**:
  - `storyContext(storyKey)` — 스토리의 제목·에픽·스프린트. 스프린트는 사이트마다 커스텀 필드 ID가 달라 REST v3로는 안정적으로 읽을 수 없으므로 이름이 고정된 **Agile API(`/rest/agile/1.0/issue/...`)를 먼저** 쓰고, 실패하면 v3의 `parent`만 읽는다(스프린트는 알 수 없음으로 둔다).
  - `currentUserName()` — `/rest/api/3/myself`의 `displayName`.
  - `listPriorities()` — `/rest/api/3/priority`. 읽지 못하면 표준 목록(Highest~Lowest)으로 대체한다.
- **기본값 규칙**: 담당자 = 지난번 값 → 없으면 나 자신. 스프린트 = 스토리의 스프린트 → 없으면 지난번 값. 우선순위 = 지난번 값 → `Low` → 목록의 가장 낮은 항목. 에픽 키 = 스토리의 에픽 → 없으면 지난번 값.
- **UI 변경**: 우선순위는 콤보박스, 기한은 `ExtendableTextField`의 `...` 버튼으로 플랫폼 `CalendarView` 대화상자(`DueDateDialog`)를 열어 고른다(직접 타이핑도 그대로 된다). 스토리에서 온 에픽 키·스프린트는 값이 있으니 접힌 상태가 되어 화면에서 사라진다 — 못 읽었을 때만 물어본다.
- **산출물**: `domain/jira/JiraDraftReader.kt`, `JiraClient`의 세 조회, `IssueDraftContext`에 `sprint`·`currentUser`·`priorities`와 기본값 함수들, `IssueDraftContextReader` 재작성, `IssueDraftForm`(우선순위 콤보·달력 버튼·기본값), `ui/issue/DueDateDialog.kt`.
- **테스트**: `IssueDraftContextTest`에 7건(에픽·스프린트 물려받기, 나 자신 기본 담당자와 기억값 우선, Low 기본 우선순위, 사이트 정의 목록 사용, 목록을 못 읽으면 표준값, 스토리에 에픽이 없으면 기억값 유지), `JiraClientTest`에 4건(Agile API 조회, 실패 시 v3 대체, 사용자·우선순위 조회, 거부되면 예외 대신 "모름"), `CreateIssueDraftDialogTest`에 4건(스토리가 알려준 값은 묻지 않음, 모르면 묻음, 우선순위 선택, 달력 확장 버튼과 날짜 적용). 총 343개 통과.

### W13. 초안이 아니라 실제로 이슈를 만든다 — (완료)
- **문제(사용자 지적)**: 확인 버튼이 "초안 복사"였고 요청 초안 JSON 미리보기가 있었다. 플러그인 사용자에게 클립보드 JSON은 아무 쓸모가 없다.
- **원인(내 판단 실수)**: CLI의 `create`가 실제 생성을 구현하지 않아(`--dry-run` 없이는 거부) "이식 대상이 없다"고 보고 초안까지만 만들었다. 이식의 목적은 CLI 코드를 옮기는 것이 아니라 그 일을 IDE에서 하게 만드는 것이므로, 실제 생성이 없으면 기능이 없는 것과 같다.
- **구현**: 확인 버튼이 `Jira 이슈 만들기`가 되고, JSON 미리보기·클립보드 복사는 제거했다. 새 포트 `JiraIssueCreator`(생성·사용자 조회·필드 ID 조회·이슈 연결)와 유스케이스 `CreateIssueUseCase`를 만들었다. CLI에 없던 기능이므로 이식이 아니라 신규 설계다.
- **사이트마다 다른 것들을 유스케이스가 흡수한다**:
  - 이슈 타입 → 설정 마법사가 저장한 타입 ID(`jira.issueTypes.*.id`)
  - 설명 → **ADF**(Jira Cloud REST v3는 설명을 Atlassian Document Format으로 받는다). `AdfDocument`가 `## 제목`은 3단 제목, `[] 항목`은 체크 목록(taskList), 나머지는 문단으로 옮긴다 — RULE.md의 결과물이 Jira에서 실제 체크박스가 된다.
  - 담당자 → 이름이 아니라 `accountId`. `/rest/api/3/user/search`로 찾는다.
  - 스토리 포인트·스프린트 → 사이트별 커스텀 필드. `/rest/api/3/field`에서 이름(`Story Points` / `Story point estimate` / `Sprint`)으로 ID를 찾는다. 스프린트 필드는 이름이 아니라 **스프린트 ID**를 받으므로 스토리에서 ID까지 읽어 둔다.
  - 스토리 ↔ 작업 → 부모 자식이 아니라 `연결된 업무 항목`이므로 생성 후 `/rest/api/3/issueLink`로 잇는다.
- **값을 넣지 못하면 실패로 만들지 않는다**: 담당자·포인트·스프린트를 못 넣었으면 이슈는 만들고 "Jira에서 직접 채워야 하는 값"으로 알림에 적는다. 연결 실패도 같다. 나머지가 다 맞는데 생성을 막는 편이 더 나쁘다. 이슈 타입 ID를 모를 때만 만들기를 거부한다(설정 마법사 안내).
- **산출물**: `domain/jira/AdfDocument.kt`, `domain/jira/JiraIssueCreator.kt`, `application/CreateIssue.kt`, `JiraClient`의 생성·사용자·필드·연결 구현, `IssueDraftView`가 JSON 대신 `CreateRequestDraft`를 들고 있음, 다이얼로그의 미리보기 제거와 `onCreate` 콜백, 액션이 백그라운드에서 생성하고 결과를 알림으로 보고(브라우저 링크 포함).
- **테스트**: `application/CreateIssueTest.kt` (11 tests — 모든 필드 매핑, ADF 설명, 스토리 연결, 연결 실패는 경고로, 못 넣은 값 보고, 스프린트 ID 없으면 미설정, 필드 조회 실패에도 계속, 타입 ID 없으면 거부, Jira 메시지 전달, 예상 못한 오류는 일반 문구, 에픽은 부모·연결 없이), `domain/jira/AdfDocumentTest.kt` (8 tests), 다이얼로그 테스트를 "확인 시 초안을 넘기는지 / 유효하지 않으면 아무것도 만들지 않는지"로 교체. CLI 동등성 JSON 비교 테스트는 직렬화를 테스트 안으로 옮겨 유지했다. 총 358개 통과.

### W14. 스프린트를 이름으로 찾아 넣기 — (완료)
- **증상**: 실제로 만들어 본 첫 이슈(S15P11A601-260)에서 "Jira에서 직접 채워야 하는 값: 스프린트(단일 JVM에서 REDIS로 변경)"가 나왔다. 스토리 포인트는 들어갔으므로 커스텀 필드 조회 자체는 성공했고, 빠진 것은 **스프린트 ID**였다.
- **원인**: Jira의 스프린트 필드는 이름이 아니라 숫자 ID를 받는다. W12까지는 그 ID를 스토리 조회 결과(`/rest/agile/1.0/issue`의 `fields.sprint.id`)에서만 가져왔기 때문에, 스토리가 활성 스프린트에 없거나 사용자가 스프린트 이름을 직접 입력한 경우에는 ID가 없어 넣을 수 없었다.
- **보완**: `JiraIssueCreator.findSprintId(projectKey, sprintName)`을 추가해, ID를 모를 때 프로젝트 보드(`/rest/agile/1.0/board?projectKeyOrId=`)의 진행 중·예정 스프린트(`state=active,future`)를 페이지 단위로 훑어 **이름이 정확히 일치하는** 스프린트를 찾는다. 같은 이름이 둘 이상이면 잘못 넣지 않도록 포기한다(`null`).
- **실패 이유를 구분한다**: 못 넣었을 때 "스프린트 필드를 찾지 못했습니다"(필드 자체가 없음)와 "진행 중·예정 스프린트에서 같은 이름을 찾지 못했습니다"(필드는 있지만 스프린트를 못 찾음)를 나눠 알린다 — 해야 할 일이 다르기 때문이다.
- **테스트**: `JiraClientTest`에 3건(보드에서 이름으로 찾기, 다음 페이지까지 탐색, 같은 이름이 둘이면 지정하지 않음), `CreateIssueTest`에 2건(스토리가 ID를 주지 않아도 이름으로 보완, 이름 조회가 실패하면 미설정) + 미설정 문구 구분 검증. 총 363개 통과.

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
