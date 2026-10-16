package io.github.gudwns1812.jiratask.domain.commit

data class CommitDraftRequest(
    val issueKey: String?,
    val branch: String?,
    val changedFiles: List<String>,
    val diff: String? = null,
    val hint: String? = null,
)

enum class CommitType(val prefix: String) {
    FEAT("feat"),
    FIX("fix"),
    DOCS("docs"),
    STYLE("style"),
    REFACTOR("refactor"),
    TEST("test"),
    CHORE("chore"),
    ;

    companion object {
        val PREFIXES: List<String> = entries.map { it.prefix }

        fun of(text: String?): CommitType? {
            val cleaned = text?.trim()?.lowercase()?.removeSuffix(":")?.trim() ?: return null

            return entries.firstOrNull { it.prefix == cleaned }
        }
    }
}

data class WrittenCommitMessage(val type: CommitType, val summary: String) {
    val subject: String get() = "${type.prefix}: $summary"
}

sealed interface CommitDraftOutcome {

    data class Written(val message: WrittenCommitMessage) : CommitDraftOutcome

    data class Unavailable(val message: String) : CommitDraftOutcome

    data class Failed(val message: String) : CommitDraftOutcome
}

fun interface CommitMessageWriter {

    fun write(request: CommitDraftRequest): CommitDraftOutcome
}

data class PendingChanges(
    val files: List<String>,
    val diff: String = "",
    val staged: Boolean = true,
)
