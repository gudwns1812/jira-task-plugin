package io.github.gudwns1812.jiratask.domain.validation

import io.github.gudwns1812.jiratask.domain.input.IssueInput
import io.github.gudwns1812.jiratask.domain.input.TaskDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueValidatorTest {

    private val validTask = IssueInput.Task(
        epicKey = "S15P11A108-10",
        storyKey = "S15P11A108-100",
        title = "[AUTH-001 | FE] 이메일 회원가입 화면 구현",
        assignee = "user@example.com",
        priority = "High",
        storyPoints = 3,
        sprint = "Sprint 1",
        dueDate = "2026-08-28",
        description = TaskDescription(
            summary = "회원가입 화면과 입력 상태를 구현한다.",
            deliverables = listOf("입력 상태와 오류 메시지가 표시된다."),
        ),
    )

    @Test
    fun `valid story computes feat label without errors`() {
        val result = IssueValidator.validate(
            IssueInput.Story(
                epicKey = "S15P11A108-10",
                title = "[AUTH-001] 이메일 회원가입",
                branch = "feat/100-signup",
            ),
        )

        assertEquals(emptyList<ValidationMessage>(), result.errors)
        assertEquals(emptyList<ValidationMessage>(), result.warnings)
        assertEquals(listOf("feat"), result.derived.labels)
    }

    @Test
    fun `valid feature task computes area label without errors`() {
        val result = IssueValidator.validate(validTask)

        assertEquals(emptyList<ValidationMessage>(), result.errors)
        assertEquals(listOf("FE"), result.derived.labels)
    }

    @Test
    fun `valid common task computes category label without errors`() {
        val result = IssueValidator.validate(validTask.copy(title = "[PLAN] 서비스 목표 정의"))

        assertEquals(emptyList<ValidationMessage>(), result.errors)
        assertEquals(listOf("기획"), result.derived.labels)
    }

    @Test
    fun `reports malformed feature id, area, and empty description together`() {
        val result = IssueValidator.validate(
            validTask.copy(
                title = "[AUTH-01 | Frontend] 회원가입 화면 구현",
                description = TaskDescription(summary = "", deliverables = emptyList()),
            ),
        )

        val paths = result.errors.map { it.path }
        assertTrue(paths.contains("title"))
        assertTrue(paths.contains("description.summary"))
        assertTrue(paths.contains("description.deliverables"))
        assertEquals(4, result.errors.size)
    }

    @Test
    fun `rejects unofficial common work category`() {
        val result = IssueValidator.validate(validTask.copy(title = "[UNKNOWN] 조사"))

        assertTrue(
            result.errors.any { it.path == "title" && it.message.contains("공식 업무 분류") },
        )
    }

    @Test
    fun `valid feature epic title passes without errors`() {
        val result = IssueValidator.validate(IssueInput.Epic(title = "[AUTH] 인증"))

        assertEquals(emptyList<ValidationMessage>(), result.errors)
    }

    @Test
    fun `warns when TECH category has no area to pick a label`() {
        val result = IssueValidator.validate(validTask.copy(title = "[TECH] 성능 검증"))

        assertEquals(0, result.errors.size)
        assertTrue(
            result.warnings.any { it.path == "title" && it.message.contains("TECH") },
        )
    }

    @Test
    fun `warns when deliverables are too short to judge completion`() {
        val result = IssueValidator.validate(validTask.copy(description = validTask.description.copy(deliverables = listOf("ok"))))

        assertTrue(
            result.warnings.any { it.path == "description.deliverables" },
        )
    }

    @Test
    fun `rejects epic title without brackets when it also has trailing brackets`() {
        val result = IssueValidator.validate(IssueInput.Epic(title = "[auth] 인증"))

        assertTrue(result.errors.any { it.path == "title" })
    }

    @Test
    fun `rejects story title with wrong sequence length`() {
        val result = IssueValidator.validate(
            IssueInput.Story(epicKey = "S15P11A108-10", title = "[AUTH-01] 이메일 회원가입", branch = "feat/1-signup"),
        )

        assertTrue(result.errors.any { it.path == "title" && it.message.contains("세 자리") })
    }
}
