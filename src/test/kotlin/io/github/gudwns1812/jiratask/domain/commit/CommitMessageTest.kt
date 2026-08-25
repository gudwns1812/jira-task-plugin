package io.github.gudwns1812.jiratask.domain.commit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CommitMessageTest {

    @Test
    fun `combines issue key and message`() {
        assertEquals(
            "S15P11A108-105 이메일 회원가입 화면 구현",
            CommitMessages.create("S15P11A108-105", "이메일 회원가입 화면 구현"),
        )
    }

    @Test
    fun `trims surrounding whitespace from the message`() {
        assertEquals(
            "S15P11A108-105 로그인 버그 수정",
            CommitMessages.create("S15P11A108-105", "  로그인 버그 수정  "),
        )
    }

    @Test
    fun `rejects an empty message`() {
        assertThrows(CommitMessageError::class.java) { CommitMessages.create("S15P11A108-105", "") }
    }

    @Test
    fun `rejects a whitespace only message`() {
        assertThrows(CommitMessageError::class.java) { CommitMessages.create("S15P11A108-105", "   ") }
    }
}
