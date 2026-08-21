# jira-task IntelliJ 플러그인 (스켈레톤)

`task-jira-cli`를 대체할 IntelliJ 플러그인의 초기 뼈대다. 지금은 `Tools` 메뉴에 자리표시자 액션 하나만 있고, 이슈 생성 폼이나 Jira/CLI 연동 로직은 아직 없다.

## 빌드 및 실행

```bash
./gradlew build
./gradlew test
./gradlew runIde
```

`runIde`로 뜬 샌드박스 IntelliJ IDEA에서 `Tools > Create Jira Issue`를 실행하면 자리표시자 메시지가 표시된다.
