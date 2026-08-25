# 그래서 플러그인 기능을 다시 줄이면

나는 오히려 이 정도가 좋다고 봐.

**핵심 기능**

```
1. Smart Commit
   - 현재 Sprint Task 빠른 검색
   - Task 선택
   - Jira key 자동 prefix
   - staged diff 기반 AI 제목 생성

2. Smart MR
   - branch commit에서 Jira key 자동 추출
   - MR 관련 Jira 작업 자동 작성
   - diff/commit 기반 AI 제목·본문 생성
   - MR 생성
```

그리고 AI 없는 보조 기능으로:

```
3. Task Start 
  - Task를 선택하고 시작 누르면 진행 중으로 바뀜.
4. Commit Guard
   - Jira key 없으면 경고
   - 존재하지 않는 key면 경고
   - 현재 Sprint Task인지 확인

5. Jira Quick Search
   - IDE에서 PROJ-81 검색
   - 제목/설명/상태 확인
   - 간단 수정

6. Jira Key Navigation
   - commit/log/code의 PROJ-81 클릭
   → Jira 업무 바로 열기
```

여기까지.

특히 **AI Task 추천, AI commit 분할, AI Jira 검색 같은 건 빼는 게 나아 보여.** 그건 Claude + MCP 같은 범용 에이전트가 훨씬 잘하는 영역이고 플러그인이 경쟁할 이유가 없어.

이렇게 잡으면 플러그인의 목적도 꽤 명확해져.

> **Jira를 AI로 대신 조작하는 플러그인이 아니라, IntelliJ의 Git workflow에 Jira를 자연스럽게 끼워 넣는 플러그인.**

