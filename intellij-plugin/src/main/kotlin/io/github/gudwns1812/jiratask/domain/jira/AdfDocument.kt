package io.github.gudwns1812.jiratask.domain.jira

/**
 * Jira Cloud REST v3는 설명을 ADF(Atlassian Document Format)로 받는다.
 * RULE.md가 정한 설명 본문(`## 설명` / `## 결과물` + `[] 항목`)을 그 형식으로 옮긴다.
 *
 * - `## 제목` → 3단 제목
 * - `[] 항목` → 체크 목록(taskList) 항목
 * - 그 밖의 줄 → 문단
 */
object AdfDocument {

    private const val HEADING_PREFIX = "## "
    private const val TASK_PREFIX = "[] "

    fun of(text: String?): Map<String, Any?>? {
        val lines = text?.lines() ?: return null
        val content = mutableListOf<Map<String, Any?>>()
        val pendingTasks = mutableListOf<String>()
        var taskListIndex = 0

        fun flushTasks() {
            if (pendingTasks.isEmpty()) {
                return
            }
            content += taskList(pendingTasks.toList(), taskListIndex++)
            pendingTasks.clear()
        }

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> flushTasks()
                trimmed.startsWith(TASK_PREFIX) -> pendingTasks += trimmed.removePrefix(TASK_PREFIX).trim()
                trimmed.startsWith(HEADING_PREFIX) -> {
                    flushTasks()
                    content += heading(trimmed.removePrefix(HEADING_PREFIX).trim())
                }
                else -> {
                    flushTasks()
                    content += paragraph(trimmed)
                }
            }
        }
        flushTasks()

        return mapOf("type" to "doc", "version" to 1, "content" to content)
    }

    private fun heading(text: String): Map<String, Any?> = mapOf(
        "type" to "heading",
        "attrs" to mapOf("level" to 3),
        "content" to listOf(textNode(text)),
    )

    private fun paragraph(text: String): Map<String, Any?> = mapOf(
        "type" to "paragraph",
        "content" to listOf(textNode(text)),
    )

    private fun taskList(items: List<String>, index: Int): Map<String, Any?> = mapOf(
        "type" to "taskList",
        "attrs" to mapOf("localId" to "jira-task-list-$index"),
        "content" to items.mapIndexed { itemIndex, item ->
            mapOf(
                "type" to "taskItem",
                "attrs" to mapOf("localId" to "jira-task-$index-$itemIndex", "state" to "TODO"),
                "content" to listOf(textNode(item)),
            )
        },
    )

    private fun textNode(text: String): Map<String, Any?> = mapOf("type" to "text", "text" to text)
}
