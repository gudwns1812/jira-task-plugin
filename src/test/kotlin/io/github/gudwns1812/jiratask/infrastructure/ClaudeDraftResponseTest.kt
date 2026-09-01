package io.github.gudwns1812.jiratask.infrastructure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Claude Code가 돌려준 것을 초안으로 읽는다. 껍질이 두 겹이라 각각을 따로 본다. */
class ClaudeDraftResponseTest {

    @Test
    fun `takes the model's words out of the cli envelope`() {
        val envelope = """{"type":"result","result":"안녕","total_cost_usd":0.01}"""

        assertEquals("안녕", ClaudeDraftResponse.textOf(envelope))
    }

    @Test
    fun `finds the envelope among lines that are not json`() {
        // 실제로 본 모양이다. MCP 서버 경고가 stdout에 함께 찍혀서 통째로 파싱하면 전부 실패한다.
        val noisy = """
            {"type":"result","result":"{\"title\":\"제목\"}","total_cost_usd":0.01}
            Client.listTools() called but server does not advertise tools capability - returning empty list
        """.trimIndent()

        assertEquals("""{"title":"제목"}""", ClaudeDraftResponse.textOf(noisy))
        assertEquals("제목", ClaudeDraftResponse.parse(ClaudeDraftResponse.textOf(noisy))?.title)
    }

    @Test
    fun `finds the envelope even when the noise comes first`() {
        val noisy = "무언가 경고\n" + """{"result":"{\"title\":\"제목\"}"}"""

        assertEquals("""{"title":"제목"}""", ClaudeDraftResponse.textOf(noisy))
    }

    @Test
    fun `passes the cli's own error message up`() {
        val envelope = """{"error":"Invalid API key · Please run /login"}"""

        assertTrue(ClaudeDraftResponse.textOf(envelope)!!.contains("/login"))
    }

    @Test
    fun `treats output that is not an envelope as the words themselves`() {
        // CLI가 형식을 바꾸더라도 최악이 "못 읽음"이 되도록, 통째로 글로 보고 한 번 더 시도한다.
        assertEquals("""{"title":"제목"}""", ClaudeDraftResponse.textOf("""{"title":"제목"}"""))
        assertEquals("그냥 글", ClaudeDraftResponse.textOf("그냥 글"))
        assertNull(ClaudeDraftResponse.textOf("   "))
    }

    @Test
    fun `reads a plain json draft`() {
        val draft = ClaudeDraftResponse.parse("""{"title":"S15-1 회원가입","description":"### 연결된 작업\n- S15-1"}""")

        assertEquals("S15-1 회원가입", draft?.title)
        assertTrue(draft!!.description.contains("### 연결된 작업"))
    }

    @Test
    fun `unwraps a draft the model fenced anyway`() {
        // JSON만 내라고 지시해도 코드펜스로 감싸 오는 일이 있다.
        val fenced = "```json\n{\"title\":\"제목\",\"description\":\"본문\"}\n```"

        assertEquals("제목", ClaudeDraftResponse.parse(fenced)?.title)
    }

    @Test
    fun `keeps only the first line of a title`() {
        val draft = ClaudeDraftResponse.parse("""{"title":"첫 줄\n둘째 줄","description":"본문"}""")

        assertEquals("첫 줄", draft?.title)
    }

    @Test
    fun `has no draft to offer when it cannot read one`() {
        // 읽지 못하면 규칙 기반 초안이 그대로 남는다. 억지로 짜내지 않는다.
        assertNull(ClaudeDraftResponse.parse(null))
        assertNull(ClaudeDraftResponse.parse(""))
        assertNull(ClaudeDraftResponse.parse("이건 JSON이 아닙니다"))
        assertNull(ClaudeDraftResponse.parse("""["배열"]"""))
        assertNull("제목 없는 초안은 쓸 수 없다", ClaudeDraftResponse.parse("""{"description":"본문만"}"""))
        assertNull(ClaudeDraftResponse.parse("""{"title":"   ","description":"본문"}"""))
    }

    @Test
    fun `accepts a draft without a description`() {
        val draft = ClaudeDraftResponse.parse("""{"title":"제목만"}""")

        assertEquals("제목만", draft?.title)
        assertEquals("", draft?.description)
    }
}
