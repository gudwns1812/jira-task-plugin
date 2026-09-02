package io.github.gudwns1812.jiratask.domain.commit

/**
 * AI에게 넘길 커밋 재료.
 *
 * MR 초안([io.github.gudwns1812.jiratask.domain.mr.DraftRequest])과 달리 **diff를 담을 수 있다** —
 * 아직 만들지 않은 커밋이라 커밋 제목처럼 사람이 이미 요약해 둔 재료가 없기 때문이다.
 * 다만 담을지 말지는 설정이 정한다. 여기까지 온 [diff]는 그 결정을 이미 통과한 값이다.
 */
data class CommitDraftRequest(
    /** 붙을 작업 키. 모델이 제목에 다시 쓰지 않도록 알려만 준다. */
    val issueKey: String?,
    val branch: String?,
    val changedFiles: List<String>,
    /** 커밋할 변경의 diff. 설정에서 끄면 null이고, 그때는 경로만 보낸다. */
    val diff: String? = null,
    /** 사용자가 이미 쓰고 있던 메시지. 힌트로만 쓴다. */
    val hint: String? = null,
)

/**
 * 커밋이 무엇을 한 것인지. 팀이 쓰는 접두사 목록이 그대로 이 타입이다.
 *
 * 목록과 순서는 팀의 커밋 훅이 받아 주는 것과 같다(`feat|fix|docs|style|refactor|test|chore`).
 * 목록을 문자열이 아니라 열거형으로 두는 이유는 [WrittenCommitMessage]가 본문을 못 담게 한 것과
 * 같다 — 프롬프트로만 부탁하면 모델은 `style`이나 `update` 같은 목록 밖의 말을 지어낸다.
 * 여기 없는 것은 애초에 값이 되지 못한다.
 */
enum class CommitType(val prefix: String) {
    FEAT("feat"),
    FIX("fix"),
    DOCS("docs"),
    STYLE("style"),
    REFACTOR("refactor"),
    TEST("test"),
    CHORE("chore"),
    ;

    companion object {

        /** 프롬프트에 그대로 싣는 목록. 지시와 검사가 갈라지지 않게 한 곳에서 나온다. */
        val PREFIXES: List<String> = entries.map { it.prefix }

        /** 모델이 낸 말에서 고른다. 목록에 없으면 null — 비슷한 것으로 바꿔 주지 않는다. */
        fun of(text: String?): CommitType? {
            val cleaned = text?.trim()?.lowercase()?.removeSuffix(":")?.trim() ?: return null

            return entries.firstOrNull { it.prefix == cleaned }
        }
    }
}

/**
 * AI가 써 준 커밋 메시지. **`<타입>: <한 줄>`이 전부다.**
 *
 * 타입을 [CommitType]으로, 본문을 담을 자리를 아예 없앤 것이 이 타입의 요점이다. 팀의 커밋은
 * `S15P11A601-152 refactor: <한 줄>` 한 형태뿐이라, 프롬프트로만 부탁해 두면 모델은 언젠가
 * 접두사를 빠뜨리거나 본문을 붙여 온다. 받을 그릇이 없으면 그런 글이 커밋 창까지 오지 못한다.
 *
 * **이슈 키는 담지 않는다** — 키를 붙이는 것은 도구의 일이고, 모델에게 맡기면 없는 번호를 지어낸다.
 */
data class WrittenCommitMessage(val type: CommitType, val summary: String) {

    /** 커밋 창에 넣을 한 줄. 이슈 키는 그 앞에 붙는다(JiraCommitMessages.withDraft). */
    val subject: String get() = "${type.prefix}: $summary"
}

/**
 * 커밋 메시지를 써 달라고 한 결과.
 *
 * MR 초안과 같이 실패를 예외가 아니라 값으로 돌려준다 — 없어도 되는 기능이라
 * 실패가 커밋을 막아서는 안 된다. 사용자는 언제나 직접 쓸 수 있다.
 */
sealed interface CommitDraftOutcome {

    data class Written(val message: WrittenCommitMessage) : CommitDraftOutcome

    /** 애초에 쓸 수 없다 — 꺼져 있거나, 설치되지 않았거나, 로그인하지 않았다. */
    data class Unavailable(val message: String) : CommitDraftOutcome

    /** 불렀는데 잘 되지 않았다 — 시한 초과, 사용량 한도, 알아볼 수 없는 응답. */
    data class Failed(val message: String) : CommitDraftOutcome
}

/**
 * 커밋 메시지를 AI에게 맡기는 경계.
 *
 * 호출은 동기(블로킹)이고 몇 초에서 몇십 초가 걸린다. 화면은 반드시 백그라운드에서 불러야 한다.
 */
fun interface CommitMessageWriter {

    fun write(request: CommitDraftRequest): CommitDraftOutcome
}

/**
 * 아직 커밋하지 않은 변경.
 *
 * 스테이지된 것이 있으면 그것만 담고, 없으면 작업 트리를 담는다([staged]가 그것을 구분한다) —
 * IDE 커밋 창은 스테이징 없이 고른 파일을 그대로 커밋하는 일이 흔해서, 스테이지만 보면
 * "변경이 없습니다"라고 말하게 된다.
 */
data class PendingChanges(
    val files: List<String>,
    /** 변경 내용. 설정에서 diff 전송을 껐으면 비어 있다. */
    val diff: String = "",
    val staged: Boolean = true,
)
