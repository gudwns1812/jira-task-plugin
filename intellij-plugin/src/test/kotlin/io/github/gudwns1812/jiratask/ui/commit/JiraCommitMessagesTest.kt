package io.github.gudwns1812.jiratask.ui.commit

import com.intellij.notification.NotificationType
import io.github.gudwns1812.jiratask.application.CreateCommitResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 커밋 메시지 접두 규칙과 결과 → 알림 매핑. */
class JiraCommitMessagesTest {

    private val issueKey = "S15P11A108-257"

    @Test
    fun `prefixes an empty message with the issue key`() {
        assertEquals("$issueKey ", JiraCommitMessages.withIssueKey(null, issueKey))
        assertEquals("$issueKey ", JiraCommitMessages.withIssueKey("", issueKey))
        assertEquals("$issueKey ", JiraCommitMessages.withIssueKey("   ", issueKey))
    }

    @Test
    fun `prefixes a plain message`() {
        assertEquals("$issueKey 이메일 회원가입 폼 구현", JiraCommitMessages.withIssueKey("이메일 회원가입 폼 구현", issueKey))
    }

    @Test
    fun `keeps a message that already has the same key`() {
        assertEquals(
            "$issueKey 이메일 회원가입",
            JiraCommitMessages.withIssueKey("$issueKey 이메일 회원가입", issueKey),
        )
    }

    @Test
    fun `replaces a stale issue key`() {
        assertEquals(
            "$issueKey 이메일 회원가입",
            JiraCommitMessages.withIssueKey("S15P11A108-100 이메일 회원가입", issueKey),
        )
        assertEquals(
            "$issueKey 이메일 회원가입",
            JiraCommitMessages.withIssueKey("OTHER-9 이메일 회원가입", issueKey),
        )
    }

    @Test
    fun `does not treat a plain word as an issue key`() {
        assertEquals("$issueKey fix: 로그인 오류", JiraCommitMessages.withIssueKey("fix: 로그인 오류", issueKey))
        assertEquals("$issueKey 회원가입-1 화면", JiraCommitMessages.withIssueKey("회원가입-1 화면", issueKey))
    }

    @Test
    fun `keeps the body of a multi line message`() {
        val message = JiraCommitMessages.withIssueKey("제목\n\n본문 설명", issueKey)

        assertTrue(message.startsWith("$issueKey 제목"))
        assertTrue(message.contains("본문 설명"))
    }

    @Test
    fun `detects a message that already starts with the key`() {
        assertTrue(JiraCommitMessages.startsWith("$issueKey 회원가입", issueKey))
        assertTrue(JiraCommitMessages.startsWith(issueKey, issueKey))
        assertFalse(JiraCommitMessages.startsWith("S15P11A108-100 회원가입", issueKey))
        assertFalse(JiraCommitMessages.startsWith(null, issueKey))
        assertFalse("다른 번호로 시작하는 키와 헷갈리지 않는다", JiraCommitMessages.startsWith("S15P11A108-2570 회원가입", issueKey))
    }

    @Test
    fun `reports a commit as information with the final message`() {
        val report = CommitReports.of(CreateCommitResult.Success(issueKey, "$issueKey 회원가입"))

        assertEquals(NotificationType.INFORMATION, report.type)
        assertEquals("커밋했습니다: $issueKey 회원가입", report.message)
    }

    @Test
    fun `reports a missing task number as an error`() {
        val report = CommitReports.of(
            CreateCommitResult.Failure(1, "현재 작업 번호가 없습니다. task start --number <작업 번호>로 저장하세요."),
        )

        assertEquals(NotificationType.ERROR, report.type)
        assertTrue(report.message.startsWith("커밋 실패: "))
        assertTrue(report.message.contains("현재 작업 번호가 없습니다"))
    }

    @Test
    fun `reports nothing staged as an error`() {
        val report = CommitReports.of(CreateCommitResult.Failure(5, "스테이지된 변경 사항이 없습니다."))

        assertEquals(NotificationType.ERROR, report.type)
        assertEquals("커밋 실패: 스테이지된 변경 사항이 없습니다.", report.message)
    }
}
