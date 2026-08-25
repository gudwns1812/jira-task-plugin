package io.github.gudwns1812.jiratask.domain.jira

import org.junit.Assert.assertEquals
import org.junit.Test

class CreateRequestDraftTest {

    @Test
    fun `draft separates jira-dependent fields from policy and relationship fields`() {
        val draft = CreateRequestDraft(
            projectKey = "S15P11A108",
            issueType = "task",
            summary = "[AUTH-001 | FE] 이메일 회원가입 화면 구현",
            description = "## 설명\n...\n\n## 결과물\n[] ...",
            labels = listOf("FE"),
            assignee = "user@example.com",
            priority = "High",
            dueDate = "2026-08-28",
            policyFields = CreateRequestDraft.PolicyFields(storyPoints = 3, sprint = "Sprint 1"),
            relationships = CreateRequestDraft.Relationships(epicKey = "S15P11A108-10", storyKey = "S15P11A108-100"),
        )

        assertEquals(CreateRequestDraft.PolicyFields(3, "Sprint 1"), draft.policyFields)
        assertEquals("S15P11A108-100", draft.relationships.storyKey)
    }
}
