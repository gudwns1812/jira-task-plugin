package io.github.gudwns1812.jiratask.infrastructure.ai

import io.github.gudwns1812.jiratask.domain.commit.CommitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 모델이 낸 글을 초안으로 읽는다. 어느 CLI를 거쳐 왔든 이 안쪽 껍질은 같다. */
class AiDraftResponseTest {

    @Test
    fun `reads a plain json draft`() {
        val draft = AiDraftResponse.parse("""{"title":"S15-1 회원가입","description":"### 연결된 작업\n- S15-1"}""")

        assertEquals("S15-1 회원가입", draft?.title)
        assertTrue(draft!!.description.contains("### 연결된 작업"))
    }

    @Test
    fun `unwraps a draft the model fenced anyway`() {
        // JSON만 내라고 지시해도 코드펜스로 감싸 오는 일이 있다.
        val fenced = "```json\n{\"title\":\"제목\",\"description\":\"본문\"}\n```"

        assertEquals("제목", AiDraftResponse.parse(fenced)?.title)
    }

    @Test
    fun `keeps only the first line of a title`() {
        val draft = AiDraftResponse.parse("""{"title":"첫 줄\n둘째 줄","description":"본문"}""")

        assertEquals("첫 줄", draft?.title)
    }

    @Test
    fun `has no draft to offer when it cannot read one`() {
        // 읽지 못하면 규칙 기반 초안이 그대로 남는다. 억지로 짜내지 않는다.
        assertNull(AiDraftResponse.parse(null))
        assertNull(AiDraftResponse.parse(""))
        assertNull(AiDraftResponse.parse("이건 JSON이 아닙니다"))
        assertNull(AiDraftResponse.parse("""["배열"]"""))
        assertNull("제목 없는 초안은 쓸 수 없다", AiDraftResponse.parse("""{"description":"본문만"}"""))
        assertNull(AiDraftResponse.parse("""{"title":"   ","description":"본문"}"""))
    }

    @Test
    fun `accepts a draft without a description`() {
        val draft = AiDraftResponse.parse("""{"title":"제목만"}""")

        assertEquals("제목만", draft?.title)
        assertEquals("", draft?.description)
    }

    @Test
    fun `reads a commit message draft`() {
        val message = AiDraftResponse.commit("""{"type":"feat","summary":"회원가입 폼 구현"}""")

        assertEquals(CommitType.FEAT, message?.type)
        assertEquals("회원가입 폼 구현", message?.summary)
        assertEquals("feat: 회원가입 폼 구현", message?.subject)
    }

    @Test
    fun `does not write the prefix twice when the model repeats it`() {
        val message = AiDraftResponse.commit("""{"type":"fix","summary":"fix: 폼 검증 오류 수정"}""")

        assertEquals("fix: 폼 검증 오류 수정", message?.subject)
    }

    @Test
    fun `takes the prefix from the summary when the type field is missing`() {
        // 형식을 어겨도 아는 접두사가 요약에 있으면 건져 낸다.
        val message = AiDraftResponse.commit("""{"summary":"refactor: AI 코드 패키지 이관"}""")

        assertEquals(CommitType.REFACTOR, message?.type)
        assertEquals("refactor: AI 코드 패키지 이관", message?.subject)
    }

    @Test
    fun `refuses a prefix that is not on the team's list`() {
        // 아무거나 골라 붙이면 잘못된 분류가 커밋 기록에 그대로 남는다.
        assertNull(AiDraftResponse.commit("""{"type":"perf","summary":"쿼리 튜닝"}"""))
        assertNull(AiDraftResponse.commit("""{"summary":"쿼리 튜닝"}"""))
    }

    @Test
    fun `throws away a body the model added anyway`() {
        // 지시해도 붙여 오는 일이 있다. 커밋 메시지는 한 줄이라(RULE.md 5.2) 여기서 떨군다.
        val message =
            AiDraftResponse.commit("""{"type":"feat","summary":"회원가입 폼 구현","body":"검증 규칙을 옮겼다."}""")

        assertEquals("feat: 회원가입 폼 구현", message?.subject)
    }

    @Test
    fun `drops a full stop the model put at the end`() {
        val message = AiDraftResponse.commit("""{"type":"docs","summary":"설정 안내 문구 정리."}""")

        assertEquals("docs: 설정 안내 문구 정리", message?.subject)
    }

    @Test
    fun `unwraps a fenced commit message`() {
        val fenced = "```json\n" + """{"type":"chore","summary":"의존성 버전 고정"}""" + "\n```"

        assertEquals("chore: 의존성 버전 고정", AiDraftResponse.commit(fenced)?.subject)
    }

    @Test
    fun `keeps only the first line of a commit summary`() {
        // 커밋 제목이 여러 줄이면 git log 한 줄 보기가 통째로 망가진다.
        val message = AiDraftResponse.commit("""{"type":"test","summary":"첫 줄\n둘째 줄"}""")

        assertEquals("test: 첫 줄", message?.subject)
    }

    @Test
    fun `has no commit message to offer when it cannot read one`() {
        assertNull(AiDraftResponse.commit(null))
        assertNull(AiDraftResponse.commit(""))
        assertNull(AiDraftResponse.commit("이건 JSON이 아닙니다"))
        assertNull("요약 없는 커밋 메시지는 쓸 수 없다", AiDraftResponse.commit("""{"type":"feat"}"""))
        assertNull(AiDraftResponse.commit("""{"type":"feat","summary":"   "}"""))
    }
}
