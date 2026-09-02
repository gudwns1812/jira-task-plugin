package io.github.gudwns1812.jiratask.domain.commit

class CommitMessageError(message: String) : Exception(message)

object CommitMessages {

    /** 팀의 커밋 훅이 요구하는 형태는 `[<이슈 키>] <type>: <요약>`이다. 키는 대괄호로 감싼다. */
    fun create(issueKey: String, message: String): String {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) {
            throw CommitMessageError("커밋 메시지를 입력하세요.")
        }

        return "[$issueKey] $trimmed"
    }
}
