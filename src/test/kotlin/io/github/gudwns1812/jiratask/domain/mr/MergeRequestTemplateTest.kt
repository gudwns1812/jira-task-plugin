package io.github.gudwns1812.jiratask.domain.mr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 팀 템플릿에 작업 줄을 끼워 넣는 규칙. 템플릿 글자는 지우지 않는다. */
class MergeRequestTemplateTest {

    private val work = listOf("S15P11A601-266 빌드 설정 정리")

    @Test
    fun `puts the work under the section that asks for related issues`() {
        val template = """
            ## 개요
            무엇을 했는지 적어주세요.

            ## 관련 이슈

            ## 테스트
        """.trimIndent()

        val filled = MergeRequestTemplate.fill(template, work)

        assertEquals(
            """
            ## 개요
            무엇을 했는지 적어주세요.

            ## 관련 이슈
            - S15P11A601-266 빌드 설정 정리

            ## 테스트
            """.trimIndent(),
            filled,
        )
    }

    @Test
    fun `keeps whatever the template already wrote under that heading`() {
        val template = "## 관련 이슈\n- (여기에 이슈 번호를 적어주세요)"

        val filled = MergeRequestTemplate.fill(template, work)

        assertTrue("우리 줄이 들어간다", filled.contains("- S15P11A601-266 빌드 설정 정리"))
        assertTrue("팀이 적어 둔 안내는 그대로 남는다", filled.contains("여기에 이슈 번호를 적어주세요"))
    }

    @Test
    fun `recognises the section however the team spelled it`() {
        listOf(
            "## 관련이슈", "### 연관 이슈", "# 연결된 작업", "## 관련 작업",
            "## Related Issues", "## related issue", "## JIRA", "## 관련 이슈:",
        ).forEach { heading ->
            val filled = MergeRequestTemplate.fill("$heading\n\n## 그 다음", work)

            assertTrue("$heading 을 알아봐야 한다", filled.lines()[1] == "- S15P11A601-266 빌드 설정 정리")
        }
    }

    @Test
    fun `does not mistake a section that merely mentions issues`() {
        // `이슈` 한 글자만 보면 이런 절에도 걸린다. 그러면 엉뚱한 자리에 작업이 박힌다.
        val template = "## 이슈 재현 방법\n1. 이렇게\n\n## 개요"

        val filled = MergeRequestTemplate.fill(template, work)

        assertTrue(filled.contains("## ${MergeRequestTemplate.APPENDED_HEADING}"))
        assertTrue("재현 방법 절은 건드리지 않는다", filled.lines()[1] == "1. 이렇게")
    }

    @Test
    fun `appends a section when the template has no place for it`() {
        val template = "## 개요\n무엇을 했는지 적어주세요."

        val filled = MergeRequestTemplate.fill(template, work)

        assertEquals(
            """
            ## 개요
            무엇을 했는지 적어주세요.

            ## 관련 이슈
            - S15P11A601-266 빌드 설정 정리
            """.trimIndent(),
            filled,
        )
    }

    @Test
    fun `leaves the template alone when there is no work to list`() {
        val template = "## 개요\n\n## 관련 이슈"

        assertEquals(template, MergeRequestTemplate.fill(template, emptyList()))
    }

    @Test
    fun `never deletes what the template said`() {
        val template = "## 개요\n중요한 안내\n\n## 관련 이슈\n\n## 체크리스트\n- [ ] 테스트"

        val filled = MergeRequestTemplate.fill(template, work)

        listOf("## 개요", "중요한 안내", "## 체크리스트", "- [ ] 테스트").forEach {
            assertTrue("$it 이 사라지면 안 된다", filled.contains(it))
        }
    }

    @Test
    fun `lists every work line once`() {
        val filled = MergeRequestTemplate.fill("## 관련 이슈", listOf("A-1 하나", "A-2 둘"))

        assertEquals("## 관련 이슈\n- A-1 하나\n- A-2 둘", filled)
    }
}
