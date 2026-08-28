package io.github.gudwns1812.jiratask.ui.search

import io.github.gudwns1812.jiratask.domain.jira.JiraIssueDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 검색창에 친 글의 해석과, 편집이 저장 가능한지의 규칙. */
class JiraSearchQueryTest {

    private fun query(input: String?, projectKey: String? = "S15P11A108") = JiraSearchQuery.of(input, projectKey)

    @Test
    fun `takes a full issue key as one issue to open`() {
        assertEquals(JiraSearchQuery.ByKey("S15P11A108-81"), query("S15P11A108-81"))
    }

    @Test
    fun `uppercases a key that was typed in lower case`() {
        assertEquals(JiraSearchQuery.ByKey("S15P11A108-81"), query("s15p11a108-81"))
    }

    @Test
    fun `completes a bare number with the configured project key`() {
        // 커밋 메시지에서 눈에 익은 형태다.
        assertEquals(JiraSearchQuery.ByKey("S15P11A108-81"), query("81"))
    }

    @Test
    fun `searches by text when a bare number has no project key to complete it`() {
        assertEquals(JiraSearchQuery.ByText("81"), query("81", projectKey = null))
        assertEquals(JiraSearchQuery.ByText("81"), query("81", projectKey = ""))
    }

    @Test
    fun `treats anything else as a summary search`() {
        assertEquals(JiraSearchQuery.ByText("회원가입"), query("회원가입"))
        assertEquals(JiraSearchQuery.ByText("AUTH-001 화면"), query("AUTH-001 화면"))
    }

    @Test
    fun `has nothing to search for when the field is blank`() {
        assertEquals(JiraSearchQuery.Empty, query(""))
        assertEquals(JiraSearchQuery.Empty, query("   "))
        assertEquals(JiraSearchQuery.Empty, query(null))
    }

    @Test
    fun `ignores the space around what was typed`() {
        assertEquals(JiraSearchQuery.ByKey("S15P11A108-81"), query("  S15P11A108-81  "))
        assertEquals(JiraSearchQuery.ByText("회원가입"), query("  회원가입  "))
    }

    private fun detail(summary: String = "제목", description: String = "설명") =
        JiraIssueDetail("S15P11A108-81", summary, description, "진행 중", "박형준", "Sprint 1")

    @Test
    fun `has nothing to save until something changes`() {
        val edit = IssueEdit(detail())

        assertFalse(edit.isModified())
        assertFalse(edit.canSave())
    }

    @Test
    fun `can save an edited summary or description`() {
        assertTrue(IssueEdit(detail()).copy(summary = "새 제목").canSave())
        assertTrue(IssueEdit(detail()).copy(description = "새 설명").canSave())
    }

    @Test
    fun `does not count a whitespace-only change as an edit`() {
        assertFalse(IssueEdit(detail(summary = "제목")).copy(summary = "  제목  ").isModified())
    }

    @Test
    fun `refuses to save a blank summary`() {
        val blank = IssueEdit(detail()).copy(summary = "   ")

        assertEquals(IssueEdit.BLANK_SUMMARY, blank.problem())
        assertFalse(blank.canSave())
    }

    @Test
    fun `allows a blank description`() {
        // 설명이 비어 있는 이슈는 흔하다. 제목만 있으면 저장할 수 있다.
        val cleared = IssueEdit(detail()).copy(description = "")

        assertNull(cleared.problem())
        assertTrue(cleared.canSave())
    }

    @Test
    fun `treats what was saved as the new original`() {
        val saved = IssueEdit(detail()).copy(summary = " 새 제목 ").saved()

        assertEquals("새 제목", saved.original.summary)
        assertFalse("저장 뒤에는 바뀐 것이 없다", saved.isModified())
        assertFalse(saved.canSave())
    }

    @Test
    fun `keeps the key it started from when saving`() {
        assertEquals("S15P11A108-81", IssueEdit(detail()).copy(summary = "새 제목").saved().original.key)
    }
}
