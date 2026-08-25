package io.github.gudwns1812.jiratask.ui.task

import com.intellij.notification.NotificationType
import io.github.gudwns1812.jiratask.application.StartTaskResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 작업 번호 입력 검증, 상태 표시줄 문구, 결과 → 알림 매핑. */
class CurrentTaskTest {

    @Test
    fun `accepts a positive integer task number`() {
        assertNull(TaskFormInput("257").problem())
        assertNull(TaskFormInput(" 257 ").problem())
        assertEquals(TaskFormInput("257"), TaskFormInput("  257  ").trimmed())
    }

    @Test
    fun `rejects a task number that is not a positive integer`() {
        listOf("", "0", "-3", "12a", "1.5").forEach { value ->
            assertEquals(
                "$value 은 작업 번호로 쓸 수 없다",
                "작업 번호는 0보다 큰 정수여야 합니다.",
                TaskFormInput(value).problem(),
            )
        }
    }

    @Test
    fun `shows the current task number in the status bar`() {
        assertEquals("Jira: 257", CurrentTaskText.of("257"))
    }

    @Test
    fun `says there is no task when nothing is stored`() {
        assertEquals(CurrentTaskText.NONE, CurrentTaskText.of(null))
        assertEquals(CurrentTaskText.NONE, CurrentTaskText.of(""))
        assertEquals(CurrentTaskText.NONE, CurrentTaskText.of("   "))
    }

    @Test
    fun `reports a saved task number as information`() {
        val report = StartTaskReports.of(StartTaskResult.Success("257"))

        assertEquals(NotificationType.INFORMATION, report.type)
        assertEquals("현재 작업 번호를 저장했습니다: 257", report.message)
    }

    @Test
    fun `reports a failure as an error with the reason`() {
        val report = StartTaskReports.of(StartTaskResult.Failure(5, "Git 저장소에서 실행해야 합니다."))

        assertEquals(NotificationType.ERROR, report.type)
        assertEquals("작업 시작 실패: Git 저장소에서 실행해야 합니다.", report.message)
    }
}
