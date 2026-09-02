package io.github.gudwns1812.jiratask.domain.mr

/**
 * AI에게 건넬 지시와 재료를 글로 만든다.
 *
 * 인프라가 아니라 도메인에 두는 이유는 **CLI를 부르지 않고 검증하기 위해서**다. 프로세스를 띄워야만
 * 확인할 수 있는 자리에 프롬프트를 두면, 무엇을 보내고 있는지 아무도 시험해 볼 수 없다.
 */
object ClaudeDraftPrompt {

    /**
     * 모델에게 늘 같게 주는 지시.
     *
     * `### 연결된 작업` 절과 이슈 키를 지키라고 못박는 것이 핵심이다. Jira가 MR을 작업에 붙이는
     * 근거는 본문에 적힌 키 문자열이라(RULE.md 5.3), AI가 그걸 지우면 기능 자체가 망가진다.
     */
    val SYSTEM: String = """
        당신은 개발자가 만들 GitLab Merge Request의 제목과 본문을 씁니다.

        규칙:
        - 오직 JSON 객체 하나만 출력합니다. {"title": "...", "description": "..."}
        - 설명, 인사, 코드펜스를 덧붙이지 않습니다.
        - 제목은 한 줄입니다. 무엇을 했는지 한눈에 보이게 씁니다.
        - 제목에 작업 키나 `[AUTH]` 같은 대괄호를 쓰지 않습니다. 키는 본문에만 적고,
          제목 앞의 대괄호는 도구가 채웁니다.
        - 본문은 "### 연결된 작업" 절로 시작하고, 주어진 작업 키를 하나도 빠뜨리지 않습니다.
          그 키 문자열이 Jira가 이 MR을 작업에 붙이는 근거입니다.
        - 주어지지 않은 사실을 지어내지 않습니다. 커밋 제목과 파일 경로에 있는 것만 씁니다.
        - 한국어로 씁니다.
        - 템플릿이 주어지면 그 양식을 그대로 지킵니다. 절과 제목을 지우거나 새로 만들지 않고,
          빈 자리만 채웁니다. 작업 키는 관련 이슈를 적는 절에 남깁니다.
    """.trimIndent()

    /**
     * stdin으로 넘길 전문. 지시와 재료를 한 덩어리로 만든다.
     *
     * `--system-prompt`로 따로 넘기지 않는 이유는 Windows 때문이다. `claude`는 npm 셈이라
     * `claude.cmd`가 `cmd.exe`를 거치는데, 인자 안의 줄바꿈이 명령줄을 끊고 큰따옴표는 따로 이스케이프해야
     * 한다. 지시문에는 둘 다 들어 있다. stdin으로 보내면 그 문제가 통째로 사라진다.
     */
    fun stdinOf(request: DraftRequest): String = "$SYSTEM\n\n$FIELDS\n${of(request)}"

    private const val FIELDS = "--- 아래는 이번 브랜치의 재료입니다 ---"

    /**
     * 이번 브랜치의 재료.
     *
     * 커밋과 파일은 [LIMIT]개까지만 싣는다. 오래 살아 있던 브랜치에서는 수백 개가 나오는데,
     * 그대로 보내면 프롬프트가 부풀고 정작 중요한 앞부분이 묻힌다.
     */
    fun of(request: DraftRequest): String = buildString {
        appendLine("원본 브랜치: ${request.sourceBranch}")
        appendLine("대상 브랜치: ${request.targetBranch}")

        appendLine()
        appendLine("작업 키(본문에 모두 남길 것):")
        if (request.issueKeys.isEmpty()) {
            appendLine("- (없음. 본문에 작업 키 절을 만들지 마세요.)")
        } else {
            request.issueKeys.forEach { appendLine("- $it") }
        }

        appendLine()
        appendLine("커밋 제목:")
        appendSection(request.commitSubjects, "(없음)")

        appendLine()
        appendLine("변경된 파일 경로(내용은 제공하지 않습니다):")
        appendSection(request.changedFiles, "(없음)")

        request.template?.trim()?.takeIf { it.isNotEmpty() }?.let { template ->
            appendLine()
            appendLine("팀이 정한 템플릿입니다. 이 양식을 지켜 본문을 쓰세요:")
            appendLine(template)
        }
    }.trimEnd()

    private fun StringBuilder.appendSection(items: List<String>, whenEmpty: String) {
        val kept = items.map { it.trim() }.filter { it.isNotEmpty() }
        if (kept.isEmpty()) {
            appendLine("- $whenEmpty")
            return
        }

        kept.take(LIMIT).forEach { appendLine("- $it") }
        if (kept.size > LIMIT) {
            appendLine("- (외 ${kept.size - LIMIT}개 생략)")
        }
    }

    const val LIMIT: Int = 50
}
