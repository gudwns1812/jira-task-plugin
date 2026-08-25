package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.jira.CreateRequestDraft
import io.github.gudwns1812.jiratask.domain.policy.WorkPolicy
import io.github.gudwns1812.jiratask.domain.policy.resolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateDraftTest {

    private val task: Map<String, Any?> = mapOf(
        "type" to "task",
        "epicKey" to "S15P11A108-10",
        "storyKey" to "S15P11A108-100",
        "title" to "[AUTH-001 | FE] 이메일 회원가입 화면 구현",
        "assignee" to "user@example.com",
        "priority" to "High",
        "storyPoints" to 3,
        "sprint" to "Sprint 1",
        "dueDate" to "2026-08-28",
        "description" to mapOf(
            "summary" to "회원가입 화면과 입력 상태를 구현한다.",
            "deliverables" to listOf("입력 상태와 오류 메시지가 표시된다."),
        ),
    )

    @Test
    fun `converts a valid task into a jira-independent create draft`() {
        val result = CreateDraftUseCase.execute(task, "S15P11A108")

        assertEquals(emptyList<Any>(), result.errors)
        assertEquals(emptyList<Any>(), result.warnings)
        assertEquals(listOf("FE"), result.derived.labels)
        assertEquals(
            CreateRequestDraft(
                projectKey = "S15P11A108",
                issueType = "task",
                summary = "[AUTH-001 | FE] 이메일 회원가입 화면 구현",
                description = "## 설명\n회원가입 화면과 입력 상태를 구현한다.\n\n## 결과물\n[] 입력 상태와 오류 메시지가 표시된다.",
                labels = listOf("FE"),
                assignee = "user@example.com",
                priority = "High",
                dueDate = "2026-08-28",
                policyFields = CreateRequestDraft.PolicyFields(storyPoints = 3, sprint = "Sprint 1"),
                relationships = CreateRequestDraft.Relationships(epicKey = "S15P11A108-10", storyKey = "S15P11A108-100"),
            ),
            result.draft,
        )
    }

    @Test
    fun `converts a valid story and epic into drafts with only the fields they need`() {
        val storyDraft = CreateDraftUseCase.execute(
            mapOf(
                "type" to "story",
                "epicKey" to "S15P11A108-10",
                "title" to "[AUTH-001] 이메일 회원가입",
                "branch" to "feat/100-signup",
                "sprint" to "Sprint 1",
            ),
            "S15P11A108",
        ).draft

        assertEquals(
            CreateRequestDraft(
                projectKey = "S15P11A108",
                issueType = "story",
                summary = "[AUTH-001] 이메일 회원가입",
                description = "`feat/100-signup`",
                labels = listOf("feat"),
                policyFields = CreateRequestDraft.PolicyFields(sprint = "Sprint 1"),
                relationships = CreateRequestDraft.Relationships(epicKey = "S15P11A108-10"),
            ),
            storyDraft,
        )

        val epicDraft = CreateDraftUseCase.execute(mapOf("type" to "epic", "title" to "[AUTH] 인증"), "S15P11A108").draft

        assertEquals(
            CreateRequestDraft(
                projectKey = "S15P11A108",
                issueType = "epic",
                summary = "[AUTH] 인증",
                description = null,
                labels = emptyList(),
                policyFields = CreateRequestDraft.PolicyFields(),
                relationships = CreateRequestDraft.Relationships(),
            ),
            epicDraft,
        )
    }

    @Test
    fun `does not build a draft when schema or business rules fail`() {
        val result = CreateDraftUseCase.execute(task + mapOf("storyPoints" to 13), "S15P11A108")

        assertNull(result.draft)
        assertTrue(result.errors.any { it.path == "storyPoints" })
    }

    @Test
    fun `uses labels computed from a custom policy`() {
        val policy = WorkPolicy.resolve(mapOf("areas" to listOf("FE", "MOBILE"), "labels" to mapOf("areas" to mapOf("MOBILE" to "Mobile"))))

        val result = CreateDraftUseCase.execute(task + mapOf("title" to "[AUTH-001 | MOBILE] 모바일 회원가입 화면 구현"), "PROJECT", policy)

        assertEquals("PROJECT", result.draft?.projectKey)
        assertEquals(listOf("Mobile"), result.draft?.labels)
    }
}
