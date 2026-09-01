package io.github.gudwns1812.jiratask.domain.mr

/**
 * AI에게 넘길 것들. **파일 내용은 담지 않는다** — 경로까지만이다.
 *
 * 무엇을 담고 무엇을 담지 않는지가 이 타입의 요점이다. 여기에 diff를 더하는 순간
 * 사내·교육기관 소스 코드가 밖으로 나가므로, 그 결정은 이 타입을 고치는 일로만 할 수 있어야 한다.
 */
data class DraftRequest(
    val sourceBranch: String,
    val targetBranch: String,
    val issueKeys: List<String>,
    val commitSubjects: List<String>,
    val changedFiles: List<String>,
    /** 팀이 정한 MR 템플릿. 있으면 이 양식을 채우게 한다. */
    val template: String? = null,
)

/** AI가 써 준 제목과 본문. */
data class WrittenDraft(val title: String, val description: String)

/**
 * 초안을 써 달라고 한 결과.
 *
 * 실패를 예외가 아니라 값으로 돌려준다 — 이 기능은 없어도 되는 것이라,
 * 실패가 MR 생성을 막아서는 안 된다. 규칙 기반 초안이 언제나 남아 있다(TASKS.md 6장).
 */
sealed interface DraftOutcome {

    data class Written(val draft: WrittenDraft) : DraftOutcome

    /** 애초에 쓸 수 없다 — 꺼져 있거나, 설치되지 않았거나, 로그인하지 않았다. 고칠 방법을 문구에 담는다. */
    data class Unavailable(val message: String) : DraftOutcome

    /** 불렀는데 잘 되지 않았다 — 시한 초과, 사용량 한도, 알아볼 수 없는 응답. */
    data class Failed(val message: String) : DraftOutcome
}

/**
 * MR 제목·본문을 AI에게 맡기는 경계.
 *
 * 호출은 동기(블로킹)이고 몇 초에서 몇십 초가 걸린다. 화면은 반드시 백그라운드에서 불러야 한다.
 */
fun interface DraftWriter {

    fun write(request: DraftRequest): DraftOutcome
}
