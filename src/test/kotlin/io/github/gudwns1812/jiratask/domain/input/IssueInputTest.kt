package io.github.gudwns1812.jiratask.domain.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IssueInputTest {

    @Test
    fun `formats task description with summary and deliverables`() {
        val task = IssueInput.Task(
            epicKey = "S15P11A108-10",
            title = "[AUTH-001 | FE] 이메일 회원가입 화면 구현",
            assignee = "user@example.com",
            priority = "High",
            storyPoints = 3,
            sprint = "Sprint 1",
            dueDate = "2026-08-28",
            description = TaskDescription(
                summary = "회원가입 화면과 입력 상태를 구현한다.",
                deliverables = listOf("입력 상태와 오류 메시지가 표시된다.", "디자인 시안과 일치한다."),
            ),
        )

        assertEquals(
            "## 설명\n회원가입 화면과 입력 상태를 구현한다.\n\n## 결과물\n[] 입력 상태와 오류 메시지가 표시된다.\n[] 디자인 시안과 일치한다.",
            task.jiraDescription(),
        )
    }

    @Test
    fun `formats story description as a code span of its branch name`() {
        val story = IssueInput.Story(
            epicKey = "S15P11A108-10",
            title = "[AUTH-001] 이메일 회원가입",
            branch = "feat/100-signup",
        )

        assertEquals("`feat/100-signup`", story.jiraDescription())
    }

    @Test
    fun `epic has no generated description`() {
        assertNull(IssueInput.Epic(title = "[AUTH] 인증").jiraDescription())
    }
}
