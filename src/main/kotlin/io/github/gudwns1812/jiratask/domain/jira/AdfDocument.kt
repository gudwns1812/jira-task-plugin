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

    private const val LINE_BREAK = "\n"

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

    /**
     * [of]가 만든 형식으로 되돌린다. Quick Search가 설명을 편집 가능한 글로 보여 줄 때 쓴다.
     *
     * ADF는 이보다 훨씬 넓은 형식이라 완전히 되돌릴 수는 없다. 모르는 노드는 그 안의 글만 건져
     * 문단으로 남긴다 — 편집하다 내용을 잃는 것보다 서식을 잃는 편이 낫다.
     */
    fun textOf(document: Any?): String {
        val content = (document as? Map<*, *>)?.get("content") as? List<*> ?: return ""

        return content.mapNotNull(::blockToText).joinToString(LINE_BREAK)
    }

    private fun blockToText(node: Any?): String? {
        val block = node as? Map<*, *> ?: return null

        return when (block["type"]) {
            "heading" -> HEADING_PREFIX + plainText(block)
            "taskList" -> (block["content"] as? List<*>).orEmpty()
                .mapNotNull { (it as? Map<*, *>)?.let { item -> TASK_PREFIX + plainText(item) } }
                .joinToString(LINE_BREAK)
            // 빈 문단은 ADF가 줄바꿈을 표현하는 방식이다. 그대로 빈 줄로 둔다.
            else -> plainText(block)
        }
    }

    /** 노드 아래 어디에 있든 `text` 값을 모은다. */
    private fun plainText(node: Map<*, *>): String {
        val builder = StringBuilder()

        fun walk(current: Any?) {
            val map = current as? Map<*, *> ?: return
            (map["text"] as? String)?.let { builder.append(it) }
            (map["content"] as? List<*>)?.forEach(::walk)
        }
        walk(node)

        return builder.toString()
    }
}
