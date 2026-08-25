package io.github.gudwns1812.jiratask.ui.branch

import com.intellij.notification.NotificationType
import io.github.gudwns1812.jiratask.application.CreateBranchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 브랜치 생성 입력 검증과 결과 → 알림 매핑. */
class CreateStoryBranchTest {

    @Test
    fun `accepts a story number and a slug name`() {
        assertNull(BranchFormInput("100", "signup").problem())
        assertNull(BranchFormInput(" 100 ", " login-form ").problem())
        assertNull(BranchFormInput("1", "a1-b2-c3").problem())
    }

    @Test
    fun `rejects a story number that is not a positive integer`() {
        assertEquals("스토리 번호는 0보다 큰 정수여야 합니다.", BranchFormInput("", "signup").problem())
        assertEquals("스토리 번호는 0보다 큰 정수여야 합니다.", BranchFormInput("0", "signup").problem())
        assertEquals("스토리 번호는 0보다 큰 정수여야 합니다.", BranchFormInput("-1", "signup").problem())
        assertEquals("스토리 번호는 0보다 큰 정수여야 합니다.", BranchFormInput("10a", "signup").problem())
    }

    @Test
    fun `rejects a name that is not a lowercase slug`() {
        listOf("Signup", "sign up", "sign_up", "-signup", "signup-", "sign--up", "").forEach { name ->
            assertEquals(
                "$name 은 브랜치 이름으로 쓸 수 없다",
                "브랜치 이름은 소문자 영문·숫자·하이픈만 사용하고 앞뒤 또는 연속 하이픈을 둘 수 없습니다.",
                BranchFormInput("100", name).problem(),
            )
        }
    }

    @Test
    fun `reports a created branch with the jira transition as information`() {
        val report = BranchReports.of(
            CreateBranchResult.Success("feat/100-signup", "S15P11A108-100", transitioned = true),
        )

        assertEquals(NotificationType.INFORMATION, report.type)
        assertTrue(report.message.contains("feat/100-signup"))
        assertTrue(report.message.contains("진행 중으로 전환"))
    }

    @Test
    fun `says the story was already in progress when no transition was needed`() {
        val report = BranchReports.of(
            CreateBranchResult.Success("feat/100-signup", "S15P11A108-100", transitioned = false),
        )

        assertEquals(NotificationType.INFORMATION, report.type)
        assertTrue(report.message.contains("이미 진행 중"))
    }

    @Test
    fun `reports a partial success as a warning`() {
        val report = BranchReports.of(
            CreateBranchResult.PartialSuccess(
                "feat/100-signup",
                "S15P11A108-100",
                "진행 중 상태로 전환할 수 있는 Jira 워크플로를 찾지 못했습니다.",
            ),
        )

        assertEquals(NotificationType.WARNING, report.type)
        assertTrue(report.message.contains("브랜치는 만들었지만"))
        assertTrue(report.message.contains("워크플로"))
    }

    @Test
    fun `reports a failure as an error with the reason`() {
        val report = BranchReports.of(CreateBranchResult.Failure(5, "커밋되지 않은 변경 사항이 있어 브랜치를 만들 수 없습니다."))

        assertEquals(NotificationType.ERROR, report.type)
        assertEquals("브랜치 생성 실패: 커밋되지 않은 변경 사항이 있어 브랜치를 만들 수 없습니다.", report.message)
    }

    @Test
    fun `trims the input before running the use case`() {
        assertEquals(BranchFormInput("100", "signup"), BranchFormInput(" 100 ", "  signup  ").trimmed())
    }
}
