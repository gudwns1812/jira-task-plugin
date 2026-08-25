package io.github.gudwns1812.jiratask.domain.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleParserTest {

    @Test
    fun `parses feature epic title`() {
        val parsed = TitleParser.parseEpicTitle("[AUTH] 인증")

        assertEquals(ParsedEpicTitle.Feature("AUTH", "인증"), parsed)
    }

    @Test
    fun `parses support epic title without brackets`() {
        val parsed = TitleParser.parseEpicTitle("개발 기반 및 인프라 구축")

        assertEquals(ParsedEpicTitle.Support("개발 기반 및 인프라 구축"), parsed)
    }

    @Test
    fun `rejects epic title with unbalanced brackets`() {
        assertNull(TitleParser.parseEpicTitle("[AUTH 인증"))
    }

    @Test
    fun `parses story title`() {
        val parsed = TitleParser.parseStoryTitle("[AUTH-001] 이메일 회원가입")

        assertEquals(ParsedStoryTitle("AUTH", "001", "이메일 회원가입"), parsed)
    }

    @Test
    fun `rejects story title missing space after bracket`() {
        assertNull(TitleParser.parseStoryTitle("[AUTH-001]이메일 회원가입"))
    }

    @Test
    fun `parses feature task title`() {
        val parsed = TitleParser.parseTaskTitle("[AUTH-001 | FE] 이메일 회원가입 화면 구현")

        assertEquals(ParsedTaskTitle.Feature("AUTH", "001", "FE", "이메일 회원가입 화면 구현"), parsed)
    }

    @Test
    fun `parses common task title with area`() {
        val parsed = TitleParser.parseTaskTitle("[PLAN | FE] 와이어프레임 제작")

        assertEquals(ParsedTaskTitle.Common("PLAN", "FE", "와이어프레임 제작"), parsed)
    }

    @Test
    fun `parses common task title without area`() {
        val parsed = TitleParser.parseTaskTitle("[PLAN] 와이어프레임 제작")

        assertEquals(ParsedTaskTitle.Common("PLAN", null, "와이어프레임 제작"), parsed)
    }

    @Test
    fun `rejects task title missing spaces around pipe`() {
        assertNull(TitleParser.parseTaskTitle("[AUTH-001|FE] 회원가입 구현"))
    }

    @Test
    fun `rejects blank title`() {
        assertNull(TitleParser.parseEpicTitle(""))
        assertNull(TitleParser.parseStoryTitle(""))
        assertNull(TitleParser.parseTaskTitle(""))
    }
}
