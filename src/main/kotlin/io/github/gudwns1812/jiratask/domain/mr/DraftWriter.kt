package io.github.gudwns1812.jiratask.domain.mr

data class DraftRequest(
    val sourceBranch: String,
    val targetBranch: String,
    val issueKeys: List<String>,
    val commitSubjects: List<String>,
    val changedFiles: List<String>,
    val template: String? = null,
)

data class WrittenDraft(val title: String, val description: String)

sealed interface DraftOutcome {

    data class Written(val draft: WrittenDraft) : DraftOutcome

    data class Unavailable(val message: String) : DraftOutcome

    data class Failed(val message: String) : DraftOutcome
}

fun interface DraftWriter {

    fun write(request: DraftRequest): DraftOutcome
}
