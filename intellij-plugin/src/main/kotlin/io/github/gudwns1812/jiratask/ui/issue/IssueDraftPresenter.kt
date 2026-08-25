package io.github.gudwns1812.jiratask.ui.issue

import io.github.gudwns1812.jiratask.application.CreateDraftUseCase
import io.github.gudwns1812.jiratask.domain.jira.CreateRequestDraft
import io.github.gudwns1812.jiratask.domain.policy.WorkPolicy
import io.github.gudwns1812.jiratask.domain.template.IssueTemplates
import io.github.gudwns1812.jiratask.domain.validation.ValidationMessage

/**
 * 이슈 생성 다이얼로그의 입력값. CLI가 JSON 파일로 받던 것을 폼 문자열로 받는 자리이며,
 * [toRawInput]이 그 문자열들을 `IssueInputParser`가 검증할 수 있는 원시 구조로 바꿔준다.
 * 빈 칸은 키 자체를 넣지 않아, 누락 필드에 대해 CLI와 같은 검증 메시지가 나오게 한다.
 */
data class IssueFormInput(
    val type: String = "task",
    val title: String = "",
    val epicKey: String = "",
    val storyKey: String = "",
    val branch: String = "",
    val sprint: String = "",
    val assignee: String = "",
    val priority: String = "",
    val storyPoints: String = "",
    val dueDate: String = "",
    val summary: String = "",
    val deliverables: String = "",
) {
    fun toRawInput(): Map<String, Any?> {
        val raw = mutableMapOf<String, Any?>("type" to type)

        when (type) {
            "epic" -> raw.putText("title", title)
            "story" -> {
                raw.putText("epicKey", epicKey)
                raw.putText("title", title)
                raw.putText("branch", branch)
                raw.putText("sprint", sprint)
            }
            "task" -> {
                raw.putText("epicKey", epicKey)
                raw.putText("storyKey", storyKey)
                raw.putText("title", title)
                raw.putText("assignee", assignee)
                raw.putText("priority", priority)
                raw.putText("sprint", sprint)
                raw.putText("dueDate", dueDate)
                storyPointsValue()?.let { raw["storyPoints"] = it }
                raw["description"] = descriptionValue()
            }
        }

        return raw
    }

    fun deliverableLines(): List<String> = deliverables.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** 숫자로 읽히면 숫자로, 아니면 입력한 문자열 그대로 넘겨 파서가 허용값 안내를 하게 한다. */
    private fun storyPointsValue(): Any? {
        val text = storyPoints.trim()
        if (text.isEmpty()) {
            return null
        }
        return text.toIntOrNull() ?: text
    }

    private fun descriptionValue(): Map<String, Any?> {
        val description = mutableMapOf<String, Any?>()
        description.putText("summary", summary)
        val lines = deliverableLines()
        if (lines.isNotEmpty()) {
            description["deliverables"] = lines
        }
        return description
    }

    private fun MutableMap<String, Any?>.putText(key: String, value: String) {
        val text = value.trim()
        if (text.isNotEmpty()) {
            this[key] = text
        }
    }
}

/**
 * 검증 결과 한 건. 어느 칸이 문제인지 사람이 읽는 이름([label])과 함께 들고 있어야
 * 화면에서 그 칸을 짚어줄 수 있다([path]는 CLI와 같은 필드 경로).
 */
data class IssueProblem(val path: String, val message: String) {
    val label: String get() = IssueFieldLabels.of(path)

    fun display(): String = "$label: $message"
}

/** 다이얼로그가 그려야 할 상태. CLI `validate`의 오류·경고 출력과 `create --dry-run`의 JSON에 대응한다. */
data class IssueDraftView(
    val errors: List<IssueProblem>,
    val warnings: List<IssueProblem>,
    val labels: List<String>,
    /** 만들 준비가 된 생성 요청. 오류가 있으면 null이다. */
    val draft: CreateRequestDraft?,
) {
    val isValid: Boolean get() = errors.isEmpty() && draft != null

    /** 문제가 있는 칸의 필드 경로 — 그 칸은 화면에 꼭 보여줘야 한다. */
    fun problemPaths(): Set<String> = (errors + warnings).map { it.path }.toSet()
}

/** 폼 입력 → 검증 결과 + 요청 초안 JSON. Swing에 의존하지 않아 그대로 단위 테스트할 수 있다. */
object IssueDraftPresenter {

    val TYPES: List<String> = listOf("epic", "story", "task")

    fun present(input: IssueFormInput, projectKey: String, policy: WorkPolicy = WorkPolicy.DEFAULT): IssueDraftView {
        val result = CreateDraftUseCase.execute(input.toRawInput(), projectKey, policy)

        return IssueDraftView(
            errors = result.errors.map(::toProblem),
            warnings = result.warnings.map(::toProblem),
            labels = result.derived.labels,
            draft = result.draft,
        )
    }

    /** 설명 입력 칸에 보여줄 안내 문구(CLI `template <type>`와 같은 내용). */
    fun templateFor(type: String): String = IssueTemplates.get(type).orEmpty()

    private fun toProblem(message: ValidationMessage): IssueProblem = IssueProblem(message.path, message.message)
}
