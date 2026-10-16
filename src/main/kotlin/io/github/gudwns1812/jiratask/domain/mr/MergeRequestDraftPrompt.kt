package io.github.gudwns1812.jiratask.domain.mr

object MergeRequestDraftPrompt {
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

    fun stdinOf(request: DraftRequest): String = "$SYSTEM\n\n$FIELDS\n${of(request)}"

    private const val FIELDS = "--- 아래는 이번 브랜치의 재료입니다 ---"

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
