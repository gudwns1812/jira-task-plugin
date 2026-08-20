# `branch create` 구현 계획

## 다음에 구현할 기능

스토리 이슈 키와 짧은 작업명을 받아 규칙에 맞는 Git 브랜치를 생성하고 전환한다.

```text
jira-task branch create --story 100 --name signup
```

성공하면 `feat/100-signup` 브랜치를 만든다. 에픽·작업 키, 잘못된 이름, 더러운 작업 디렉터리, 이미 존재하는 브랜치에서는 Git 상태를 바꾸지 않고 종료 코드 `1`을 반환한다.

## 선행 의존성

`RULE.md`는 브랜치를 스토리에서만 만들도록 정한다. 따라서 CLI는 브랜치를 만들기 전에 Jira에서 `--story` 이슈를 조회해 유형이 `story`인지 확인해야 한다.

프로젝트 키는 `jira-task config set --project S15P11A108`으로 한 번 설정한다. 명령은 설정된 프로젝트 키와 `--story` 번호를 조립해 전체 Jira 키(`S15P11A108-100`)를 만든다. 설정이 없으면 Git 명령을 실행하지 않고 설정 방법을 안내한다.

현재 저장소에는 Jira HTTP 어댑터가 없으므로, 이번 단계에서는 다음 경계를 먼저 정의한다.

```text
CLI
  → Application(create-branch)
      → StoryIssueReader 포트 (Jira 조회)
      → GitRepository 포트 (상태 검사·브랜치 생성)
```

- 단위 테스트는 메모리 `StoryIssueReader`와 `GitRepository`로 모든 성공·실패 경로를 검증한다.
- 실제 CLI는 Jira 연결 정보가 없을 때 Git 명령을 실행하지 않고 설정 오류를 안내한다.
- 다음 Jira 연결 단계에서 `StoryIssueReader`의 실제 REST 어댑터와 설정을 연결한다. 어댑터가 준비되기 전에는 실제 브랜치 생성 명령을 활성화하지 않는다.

## 동작 흐름

```text
--story 번호와 --name 형식 검사
  → 기본 프로젝트 키와 조립해 Jira 이슈 조회
  → 이슈 유형이 story인지 확인
  → 브랜치명 feat/<이슈 키 마지막 번호>-<name> 조립
  → Git 저장소·현재 작업 디렉터리 상태 검사
  → 동일 로컬/원격 브랜치 검사
  → git switch -c <branch> [--from]
  → 생성된 브랜치명 출력
```

`--allow-dirty`를 지정하지 않으면 커밋되지 않은 변경 사항이 있을 때 중단한다. `--checkout-existing`은 같은 이름의 **로컬** 브랜치가 있을 때만 전환을 허용하며, 원격 브랜치만 있을 경우에는 안전하게 중단한다.

## CLI 인터페이스

```text
jira-task branch create \
  --story 100 \
  --name signup \
  [--from develop] \
  [--allow-dirty] \
  [--checkout-existing]
```

- `--story`: 현재 프로젝트의 Jira 스토리 번호. 양의 정수여야 한다.
- `--name`: 소문자 영문·숫자·하이픈만 사용한다. 앞뒤 하이픈과 연속 하이픈은 허용하지 않는다.
- `--from`: 생략 시 현재 체크아웃된 브랜치를 기준으로 한다.
- `--allow-dirty`: 사용자가 명시적으로 지정한 경우에만 더러운 작업 디렉터리 검사를 우회한다.
- `--checkout-existing`: 이미 존재하는 로컬 브랜치로의 전환만 허용한다.

## 만들 파일

```text
src/
├─ application/create-branch.ts          # 스토리 확인 → Git 안전 검사 → 생성 흐름
├─ domain/branch/branch-name.ts           # 스토리 번호·짧은 이름 검증과 브랜치명 조립
├─ domain/jira/story-issue-reader.ts      # Jira 읽기 포트와 스토리 조회 결과 타입
├─ infrastructure/git-repository.ts       # git status/branch/switch 어댑터
└─ cli/index.ts                            # branch create 명령 및 옵션·오류 출력

tests/
├─ application/create-branch.test.ts
├─ domain/branch/branch-name.test.ts
└─ infrastructure/git-repository.test.ts
```

## 테스트 시나리오

- 설정된 프로젝트 키와 스토리 번호 `100`, `signup`이 `S15P11A108-100` 조회 및 `feat/100-signup` 생성으로 변환된다.
- 에픽·작업 등 스토리가 아닌 Jira 이슈는 Git 명령 전에 거부한다.
- 키 형식 오류, 이름 대문자/공백/연속 하이픈, 번호 누락을 거부한다.
- Git 저장소가 아니거나 작업 디렉터리가 더러우면 중단하며, `--allow-dirty`일 때만 진행한다.
- 로컬 또는 원격 동명 브랜치는 생성하지 않는다. 로컬 브랜치와 `--checkout-existing` 조합만 전환한다.
- Git 프로세스 실패와 Jira 조회 실패는 원인을 노출하되 토큰·인증 헤더는 출력하지 않는다.

## 완료 기준

- 브랜치 이름 규칙과 Git 안전 검사가 단위·통합 테스트로 검증된다.
- Jira 스토리 확인이 실패하거나 설정이 없으면 작업 트리·브랜치를 변경하지 않는다.
- 실제 Git 테스트 저장소에서 정상 생성, 더러운 상태 오류, 중복 브랜치 오류를 확인한다.
- 전체 테스트, 타입 검사, 빌드, 빌드된 CLI의 `--help`와 변경 명령의 정상·오류 실행이 통과한다.
