package io.github.gudwns1812.jiratask.domain.input

data class TaskDescription(
    val summary: String,
    val deliverables: List<String>,
)

sealed class IssueInput {
    abstract val title: String

    data class Epic(
        override val title: String,
    ) : IssueInput()

    data class Story(
        val epicKey: String,
        override val title: String,
        val branch: String,
        val sprint: String? = null,
    ) : IssueInput()

    data class Task(
        val epicKey: String,
        val storyKey: String? = null,
        override val title: String,
        val assignee: String,
        val priority: String,
        val storyPoints: Int,
        val sprint: String,
        val dueDate: String,
        val description: TaskDescription,
    ) : IssueInput()

    /** RULE.md에 정의된 이슈 유형별 설명 본문을 만든다. */
    fun jiraDescription(): String? = when (this) {
        is Epic -> null
        is Story -> "`$branch`"
        is Task -> buildString {
            append("## 설명\n")
            append(description.summary)
            append("\n\n## 결과물")
            description.deliverables.forEach { deliverable ->
                append("\n[] ")
                append(deliverable)
            }
        }
    }
}
