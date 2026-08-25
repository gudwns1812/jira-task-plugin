package io.github.gudwns1812.jiratask.domain.commit

class CommitMessageError(message: String) : Exception(message)

object CommitMessages {
    fun create(issueKey: String, message: String): String {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) {
            throw CommitMessageError("커밋 메시지를 입력하세요.")
        }

        return "$issueKey $trimmed"
    }
}
