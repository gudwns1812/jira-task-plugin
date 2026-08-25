package io.github.gudwns1812.jiratask.domain.jira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** RULE.md의 설명 본문을 Jira Cloud가 받는 ADF로 옮기는 규칙. */
class AdfDocumentTest {

    private val taskDescription = listOf(
        "## 설명",
        "이메일 회원가입 폼을 만든다.",
        "",
        "## 결과물",
        "[] 회원가입 화면",
        "[] 유효성 검사",
    ).joinToString("\n")

    @Suppress("UNCHECKED_CAST")
    private fun contentOf(text: String?): List<Map<String, Any?>> =
        AdfDocument.of(text)?.get("content") as List<Map<String, Any?>>

    @Test
    fun `has nothing to convert without a description`() {
        assertNull(AdfDocument.of(null))
    }

    @Test
    fun `turns headings deliverables and plain lines into their own nodes`() {
        val content = contentOf(taskDescription)

        assertEquals(listOf("heading", "paragraph", "heading", "taskList"), content.map { it["type"] })
    }

    @Test
    fun `writes a heading at level three`() {
        val heading = contentOf("## 설명").single()

        assertEquals(mapOf("level" to 3), heading["attrs"])
        assertEquals(listOf(mapOf("type" to "text", "text" to "설명")), heading["content"])
    }

    @Test
    fun `makes each deliverable a check item`() {
        val taskList = contentOf(listOf("[] 회원가입 화면", "[] 유효성 검사").joinToString("\n")).single()

        @Suppress("UNCHECKED_CAST")
        val items = taskList["content"] as List<Map<String, Any?>>
        assertEquals(2, items.size)
        assertEquals("taskItem", items[0]["type"])
        assertEquals("TODO", (items[0]["attrs"] as Map<*, *>)["state"])
        assertEquals(listOf(mapOf("type" to "text", "text" to "회원가입 화면")), items[0]["content"])
    }

    @Test
    fun `gives every check item its own local id`() {
        val taskList = contentOf(listOf("[] 하나", "[] 둘").joinToString("\n")).single()

        @Suppress("UNCHECKED_CAST")
        val items = taskList["content"] as List<Map<String, Any?>>
        val ids = items.map { (it["attrs"] as Map<*, *>)["localId"] }
        assertEquals(ids.distinct(), ids)
    }

    @Test
    fun `keeps separate check lists apart`() {
        val content = contentOf(listOf("[] 하나", "", "문단", "[] 둘").joinToString("\n"))

        assertEquals(listOf("taskList", "paragraph", "taskList"), content.map { it["type"] })
    }

    @Test
    fun `writes a plain story description as one paragraph`() {
        val content = contentOf("`feat/100-signup`")

        assertEquals(listOf("paragraph"), content.map { it["type"] })
        assertEquals(listOf(mapOf("type" to "text", "text" to "`feat/100-signup`")), content.single()["content"])
    }

    @Test
    fun `declares the document version jira expects`() {
        val document = AdfDocument.of("설명")!!

        assertEquals("doc", document["type"])
        assertEquals(1, document["version"])
    }
}
