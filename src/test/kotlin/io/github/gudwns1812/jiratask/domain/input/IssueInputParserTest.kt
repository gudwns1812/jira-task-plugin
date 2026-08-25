package io.github.gudwns1812.jiratask.domain.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueInputParserTest {

    private val validTaskJson: Map<String, Any?> = mapOf(
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
    fun `parses a valid epic`() {
        val result = IssueInputParser.parse(mapOf("type" to "epic", "title" to "[AUTH] 인증"))

        assertEquals(IssueInputParseResult.Success(IssueInput.Epic("[AUTH] 인증")), result)
    }

    @Test
    fun `parses a valid story with optional sprint omitted`() {
        val result = IssueInputParser.parse(
            mapOf(
                "type" to "story",
                "epicKey" to "S15P11A108-10",
                "title" to "[AUTH-001] 이메일 회원가입",
                "branch" to "feat/100-signup",
            ),
        )

        assertEquals(
            IssueInputParseResult.Success(
                IssueInput.Story("S15P11A108-10", "[AUTH-001] 이메일 회원가입", "feat/100-signup", null),
            ),
            result,
        )
    }

    @Test
    fun `parses a valid task`() {
        val result = IssueInputParser.parse(validTaskJson)

        assertTrue(result is IssueInputParseResult.Success)
        val task = (result as IssueInputParseResult.Success).input as IssueInput.Task
        assertEquals(3, task.storyPoints)
        assertEquals("2026-08-28", task.dueDate)
    }

    @Test
    fun `rejects input that is not a JSON object`() {
        val result = IssueInputParser.parse("not-an-object")

        assertTrue(result is IssueInputParseResult.Failure)
        assertTrue((result as IssueInputParseResult.Failure).errors.any { it.path == "input" })
    }

    @Test
    fun `rejects unknown type discriminator`() {
        val result = IssueInputParser.parse(mapOf("type" to "subtask", "title" to "no"))

        assertTrue(result is IssueInputParseResult.Failure)
    }

    @Test
    fun `reports every missing required field for a task`() {
        val result = IssueInputParser.parse(mapOf("type" to "task"))

        assertTrue(result is IssueInputParseResult.Failure)
        val paths = (result as IssueInputParseResult.Failure).errors.map { it.path }
        assertTrue(paths.containsAll(listOf("epicKey", "title", "assignee", "priority", "storyPoints", "sprint", "dueDate", "description")))
    }

    @Test
    fun `rejects unknown fields on a strict object`() {
        val result = IssueInputParser.parse(mapOf("type" to "epic", "title" to "[AUTH] 인증", "extra" to "nope"))

        assertTrue(result is IssueInputParseResult.Failure)
        assertTrue((result as IssueInputParseResult.Failure).errors.any { it.path == "extra" })
    }

    @Test
    fun `rejects a calendar-invalid due date`() {
        val invalid = validTaskJson + mapOf("dueDate" to "2026-02-30")

        val result = IssueInputParser.parse(invalid)

        assertTrue(result is IssueInputParseResult.Failure)
        assertTrue((result as IssueInputParseResult.Failure).errors.any { it.path == "dueDate" })
    }

    @Test
    fun `rejects a malformed due date string`() {
        val invalid = validTaskJson + mapOf("dueDate" to "2026/08/28")

        val result = IssueInputParser.parse(invalid)

        assertTrue(result is IssueInputParseResult.Failure)
        assertTrue((result as IssueInputParseResult.Failure).errors.any { it.path == "dueDate" })
    }

    @Test
    fun `rejects storyPoints outside the allowed set`() {
        val invalid = validTaskJson + mapOf("storyPoints" to 4)

        val result = IssueInputParser.parse(invalid)

        assertTrue(result is IssueInputParseResult.Failure)
        assertTrue((result as IssueInputParseResult.Failure).errors.any { it.path == "storyPoints" })
    }

    @Test
    fun `rejects empty deliverables list`() {
        val invalid = validTaskJson + mapOf(
            "description" to mapOf("summary" to "설명", "deliverables" to emptyList<String>()),
        )

        val result = IssueInputParser.parse(invalid)

        assertTrue(result is IssueInputParseResult.Failure)
        assertTrue((result as IssueInputParseResult.Failure).errors.any { it.path == "description.deliverables" })
    }
}
