package io.github.gudwns1812.jiratask.domain.jira

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
            else -> plainText(block)
        }
    }

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
