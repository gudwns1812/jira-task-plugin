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

### W2. 툴 윈도우 골격을 `SimpleToolWindowPanel` + 툴바로 교체
- **내용**: `JiraTaskToolWindowPanel`을 `SimpleToolWindowPanel(vertical = true, borderless = true)` 기반으로 다시 만든다. 상단에 `ActionManager.createActionToolbar("JiraTaskToolWindow", group, true)`, 본문은 `JBScrollPane`. `plugin.xml`에 툴바용 액션 그룹 `JiraTask.ToolWindowToolbar`를 선언하고 기존 액션 4개(이슈 초안·브랜치·작업 시작·커밋)를 넣는다. `button()` 6개는 제거한다.
- **의존**: 없음(W1과 독립).
- **완료 기준**: 툴바 액션 그룹의 자식 액션 ID가 기대 순서와 같은지, 패널에 `JButton`이 더 이상 없는지, 툴바 `targetComponent`가 설정되어 있는지(미설정 시 플랫폼 경고 발생) 테스트.
- **산출물 예상**: `ui/toolwindow/JiraTaskToolWindowPanel.kt`, `plugin.xml` 액션 그룹.

### W3. 액션에 활성/비활성 판단 넣기
- **내용**: `CreateIssueDraftAction`·`CreateStoryBranchAction`·`StartTaskAction`·`CommitWithJiraTaskAction`의 `update()`가 `JiraTaskSettings` 결과까지 보고 `isEnabled`를 정하고, 비활성일 때 `presentation.description`에 이유를 넣는다(툴팁으로 보인다). 각 액션에 아이콘(`AllIcons.*`)을 지정한다. 툴바가 액션을 직접 실행하므로 `performAction`/`createFromDataContext` 경로는 삭제한다.
- **의존**: W2.
- **완료 기준**: 설정 없음 / 프로젝트 키만 있음 / 전부 있음 세 경우에 대해 액션 4개의 `isEnabled`와 툴팁 문구를 검증(`TestActionEvent`). 설정이 없을 때 액션을 실행해도 예외가 나지 않고 안내 알림만 뜨는 기존 동작은 유지(툴바 밖 메뉴에서 실행될 수 있으므로).
- **주의**: `update()`는 BGT에서 돈다(`ActionUpdateThread.BGT`). 설정 조회는 인메모리라 괜찮지만 **git 호출은 넣지 않는다**.

### W4. 상태 표시를 제대로 만들기
- **내용**: 값 라벨을 `JBLabel().apply { setCopyable(true) }`로, 없는 값은 `NamedColorUtil.getInactiveTextColor()` 회색 자리 문구로, Jira 주소는 `ActionLink` + `BrowserUtil.browse`로, 이슈 키·브랜치는 모노스페이스 폰트로. 행 라벨과 값의 정렬·여백을 `panel { row(...) }` 기본 간격에 맞춘다.
- **의존**: W2.
- **완료 기준**: 설정된 상태에서 값이 복사 가능(`isCopyable`)하고, 미설정 값은 회색이며, Jira 주소 컴포넌트가 `ActionLink`이고 그 링크 대상이 저장된 baseUrl과 같은지 테스트. 이메일·토큰이 어떤 컴포넌트 텍스트에도 없는지 확인하는 기존 성격의 테스트 유지.

### W5. 미설정·손상 상태를 빈 상태로 보여주기
- **내용**: 프로젝트 키가 없으면 상태 행 대신 `JBPanelWithEmptyText`의 `emptyText`에 안내 + `설정 마법사 실행`·`설정 열기` 링크(`SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES` + 클릭 리스너)를 보여준다. 설정이 손상된 경우에는 경고 아이콘과 오류 문구, `설정 열기` 링크를 보여준다.
- **의존**: W2, W4. `JiraTaskStatus.isConfigured`/`problem`은 이미 있으므로 그대로 쓴다.
- **완료 기준**: 미설정 / 손상 / 정상 세 상태에서 보이는 컴포넌트가 각각 빈 상태·오류·상태 행인지, 링크 클릭이 마법사·설정 화면 호출로 이어지는지(호출을 람다로 주입해 검증) 테스트.

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
