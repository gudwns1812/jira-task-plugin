package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.policy.WorkPolicy
import io.github.gudwns1812.jiratask.domain.policy.resolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidateIssueTest {

    private val validTask: Map<String, Any?> = mapOf(
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
    fun `returns labels for a task passing both schema and business rules`() {
        val result = ValidateIssueUseCase.execute(validTask)

        assertEquals(emptyList<Any>(), result.errors)
        assertEquals(emptyList<Any>(), result.warnings)
        assertEquals(listOf("FE"), result.derived.labels)
    }

    @Test
    fun `returns schema errors and rule errors from their own stages`() {
        val schemaError = ValidateIssueUseCase.execute(validTask + mapOf("assignee" to "", "storyPoints" to 13))
        val ruleError = ValidateIssueUseCase.execute(validTask + mapOf("title" to "[AUTH-01 | Frontend] 화면 구현"))

        val schemaPaths = schemaError.errors.map { it.path }
        assertTrue(schemaPaths.contains("assignee"))
        assertTrue(schemaPaths.contains("storyPoints"))

        assertTrue(ruleError.errors.any { it.path == "title" && it.message.contains("세 자리") })
        assertTrue(ruleError.errors.any { it.path == "title" && it.message.contains("담당 영역") })
    }

    @Test
    fun `uses areas and labels added by a custom policy`() {
        val policy = WorkPolicy.resolve(mapOf("areas" to listOf("FE", "MOBILE"), "labels" to mapOf("areas" to mapOf("MOBILE" to "Mobile"))))

        val result = ValidateIssueUseCase.execute(validTask + mapOf("title" to "[AUTH-001 | MOBILE] 모바일 회원가입 화면 구현"), policy)

        assertEquals(emptyList<Any>(), result.errors)
        assertEquals(listOf("Mobile"), result.derived.labels)
    }
}
