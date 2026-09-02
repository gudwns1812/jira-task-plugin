package io.github.gudwns1812.jiratask.domain.commit

/**
 * 커밋 메시지를 써 달라는 지시와 재료를 글로 만든다.
 *
 * [io.github.gudwns1812.jiratask.domain.mr.ClaudeDraftPrompt]와 같은 이유로 도메인에 둔다 —
 * 프로세스를 띄우지 않고도 **무엇을 보내고 있는지** 시험해 볼 수 있어야 한다.
 * diff를 함께 보내는 기능이라 그 확인이 MR 초안보다 더 중요하다.
 */
object CommitDraftPrompt {

    /**
     * 모델에게 늘 같게 주는 지시.
     *
     * 세 가지를 못박는다. **접두사를 붙일 것** — 팀의 커밋은 `refactor: …`처럼 무엇을 한 것인지로
     * 시작한다. **이슈 키를 쓰지 말 것** — 키는 도구가 앞에 붙이므로(RULE.md 5.2) 모델까지 쓰면
     * `S15P11A601-152 S15P11A601-152 …`이 된다. **본문을 쓰지 말 것** — 커밋은 한 줄이다.
     *
     * 접두사 목록은 [CommitType]에서 가져온다. 지시와 검사가 갈라지면 모델은 목록에 없는 말을
     * 배우고, 그 답은 읽히지 못해 버려진다.
     */
    val SYSTEM: String = """
        당신은 개발자가 지금 만들 Git 커밋의 제목을 씁니다.

        규칙:
        - 오직 JSON 객체 하나만 출력합니다. {"type": "...", "summary": "..."}
        - 설명, 인사, 코드펜스, 본문을 덧붙이지 않습니다. 출력에는 그 두 값뿐입니다.
        - type은 다음 중 정확히 하나입니다: ${CommitType.PREFIXES.joinToString(", ")}
          feat=기능 추가, fix=버그 수정, docs=문서·주석, style=동작을 바꾸지 않는 서식·정렬,
          refactor=동작을 바꾸지 않는 구조 변경, test=테스트 추가·수정,
          chore=빌드·설정·의존성 등 그 밖의 일.
        - summary는 **한 줄**이고 짧게(50자 안팎) 씁니다. 줄바꿈을 넣지 않습니다.
        - summary에는 type을 다시 쓰지 않습니다. 도구가 `type: summary`로 합칩니다.
        - 이 커밋이 무엇을 했는지 한눈에 보이게, `칭호 부여 배선과 종료 프레임 전달`처럼 명사로 끝맺습니다.
        - 마침표로 끝내지 않습니다.
        - Jira 이슈 키를 쓰지 않습니다. 키는 도구가 맨 앞에 붙입니다.
        - 주어지지 않은 사실을 지어내지 않습니다. 파일 경로와 변경 내용에 있는 것만 씁니다.
        - 여러 가지가 섞여 있으면 **가장 중요한 것 하나만** 제목으로 삼습니다. 나열하지 않습니다.
        - 한국어로 씁니다.
    """.trimIndent()

    /** stdin으로 넘길 전문. 인자가 아니라 stdin인 이유는 MR 초안 쪽에 적어 두었다. */
    fun stdinOf(request: CommitDraftRequest): String = "$SYSTEM\n\n$FIELDS\n${of(request)}"

    private const val FIELDS = "--- 아래는 이번 커밋의 재료입니다 ---"

    /**
     * 이번 커밋의 재료.
     *
     * 파일은 [FILE_LIMIT]개, diff는 [DIFF_LINE_LIMIT]줄·[DIFF_CHAR_LIMIT]자까지만 싣는다.
     * 큰 커밋에서는 diff가 수만 줄이 되는데, 그대로 보내면 프롬프트가 부풀고 앞부분이 묻힌다.
     */
    fun of(request: CommitDraftRequest): String = buildString {
        request.branch?.trim()?.takeIf { it.isNotEmpty() }?.let { appendLine("브랜치: $it") }
        request.issueKey?.trim()?.takeIf { it.isNotEmpty() }?.let {
            appendLine("작업 키: $it (도구가 메시지 앞에 붙입니다. subject에는 쓰지 마세요.)")
        }

        appendLine()
        appendLine("변경된 파일 경로:")
        appendFiles(request.changedFiles)

        request.hint?.trim()?.takeIf { it.isNotEmpty() }?.let {
            appendLine()
            appendLine("사용자가 쓰던 메모입니다. 참고하되 그대로 베끼지는 마세요:")
            appendLine(it)
        }

        request.diff?.trim()?.takeIf { it.isNotEmpty() }?.let {
            appendLine()
            appendLine("변경 내용(diff):")
            appendLine(clip(it))
        }
    }.trimEnd()

    private fun StringBuilder.appendFiles(files: List<String>) {
        val kept = files.map { it.trim() }.filter { it.isNotEmpty() }
        if (kept.isEmpty()) {
            appendLine("- (없음)")
            return
        }

        kept.take(FILE_LIMIT).forEach { appendLine("- $it") }
        if (kept.size > FILE_LIMIT) {
            appendLine("- (외 ${kept.size - FILE_LIMIT}개 생략)")
        }
    }

    /** 줄 수와 글자 수 중 먼저 걸리는 쪽에서 자르고, 잘랐다는 것을 밝힌다. */
    fun clip(diff: String): String {
        val lines = diff.lineSequence().toList()
        val kept = lines.take(DIFF_LINE_LIMIT).joinToString("\n")
        val clipped = kept.length > DIFF_CHAR_LIMIT || lines.size > DIFF_LINE_LIMIT

        return if (clipped) "${kept.take(DIFF_CHAR_LIMIT)}\n$CLIPPED" else kept
    }

    const val FILE_LIMIT: Int = 50
    const val DIFF_LINE_LIMIT: Int = 600
    const val DIFF_CHAR_LIMIT: Int = 30000
    const val CLIPPED: String = "(diff가 길어 여기서 잘랐습니다. 나머지는 보내지 않았습니다.)"
}
