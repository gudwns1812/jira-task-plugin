package io.github.gudwns1812.jiratask.domain.jira

import org.junit.Assert.assertEquals
import org.junit.Test

/** ADF를 편집 가능한 글로 되돌리는 쪽. Quick Search가 설명을 보여 줄 때 쓴다. */
class AdfTextTest {

    private fun lines(vararg line: String) = line.joinToString("\n")

    @Test
    fun `round-trips the format this plugin writes`() {
        val text = lines("## 설명", "화면을 만든다.", "[] 입력 검증", "[] 오류 표시")

        assertEquals(text, AdfDocument.textOf(AdfDocument.of(text)))
    }

    @Test
    fun `has nothing to show for a description jira never set`() {
        assertEquals("", AdfDocument.textOf(null))
        assertEquals("", AdfDocument.textOf(emptyMap<String, Any?>()))
    }

    @Test
    fun `has nothing to show for a document with no content`() {
        assertEquals("", AdfDocument.textOf(mapOf("type" to "doc", "content" to emptyList<Any>())))
    }

    @Test
    fun `keeps the text of a node it does not understand`() {
        // 편집하다 내용을 잃는 것보다 서식을 잃는 편이 낫다.
        val document = mapOf(
            "type" to "doc",
            "content" to listOf(
                mapOf(
                    "type" to "bulletList",
                    "content" to listOf(
                        mapOf("type" to "listItem", "content" to listOf(mapOf("type" to "text", "text" to "항목"))),
                    ),
                ),
            ),
        )

        assertEquals("항목", AdfDocument.textOf(document))
    }

    @Test
    fun `joins the pieces of a paragraph that jira split by formatting`() {
        val document = mapOf(
            "type" to "doc",
            "content" to listOf(
                mapOf(
                    "type" to "paragraph",
                    "content" to listOf(
                        mapOf("type" to "text", "text" to "굵은 "),
                        mapOf("type" to "text", "text" to "글씨", "marks" to listOf(mapOf("type" to "strong"))),
                    ),
                ),
            ),
        )

        assertEquals("굵은 글씨", AdfDocument.textOf(document))
    }

    @Test
    fun `keeps an empty paragraph as a blank line`() {
        val document = mapOf(
            "type" to "doc",
            "content" to listOf(
                mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to "위"))),
                mapOf("type" to "paragraph"),
                mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to "아래"))),
            ),
        )

        assertEquals(lines("위", "", "아래"), AdfDocument.textOf(document))
    }

    @Test
    fun `survives a description that is not a document at all`() {
        // 아주 오래된 이슈는 설명이 평문 문자열로 남아 있을 수 있다.
        assertEquals("", AdfDocument.textOf("그냥 글자"))
        assertEquals("", AdfDocument.textOf(listOf(1, 2, 3)))
    }
}
