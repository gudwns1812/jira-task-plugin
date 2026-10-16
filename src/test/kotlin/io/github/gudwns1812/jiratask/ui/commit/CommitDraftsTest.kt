package io.github.gudwns1812.jiratask.ui.commit

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.gudwns1812.jiratask.application.CommitDraftResult
import io.github.gudwns1812.jiratask.application.CredentialStore
import io.github.gudwns1812.jiratask.application.DraftCommitMessageUseCase
import io.github.gudwns1812.jiratask.domain.commit.PendingChanges
import io.github.gudwns1812.jiratask.infrastructure.JiraTaskConfigStorage
import io.github.gudwns1812.jiratask.infrastructure.PolicyConfigRepository
import io.github.gudwns1812.jiratask.ui.JiraTaskSettings
import java.io.File

/** 두 커밋 창이 함께 쓰는 초안 준비. 여기서 갈라지면 창마다 결과가 달라진다. */
class CommitDraftsTest : BasePlatformTestCase() {

    private object NoCredentials : CredentialStore {
        override fun save(baseUrl: String, email: String, apiToken: String) = Unit
        override fun get(baseUrl: String, email: String): String? = null
    }

    private fun drafts(): CommitDrafts =
        CommitDrafts(JiraTaskSettings(PolicyConfigRepository(JiraTaskConfigStorage()), NoCredentials) { null })

    private fun directory() = File(project.basePath ?: ".")

    fun `test the hint drops the issue key the commit window already put there`() {
        // 키가 붙은 채로 넘기면 모델이 "이렇게 쓰기로 했구나" 하고 제목에 키를 다시 적는다.
        val state = drafts().stateFor(project, directory(), "S15P11A108-257 폼 검증 고침")

        assertEquals("폼 검증 고침", state.hint)
    }

    fun `test a message that is only an issue key is no hint at all`() {
        assertNull(drafts().stateFor(project, directory(), "S15P11A108-257 ").hint)
        assertNull(drafts().stateFor(project, directory(), "   ").hint)
        assertNull(drafts().stateFor(project, directory(), null).hint)
    }

    fun `test keeps a hint that never had an issue key`() {
        assertEquals("폼 검증 고침", drafts().stateFor(project, directory(), "폼 검증 고침").hint)
    }

    fun `test refuses to draft while the ai is turned off`() {
        // 설정에 ai 절이 없다. 이때 CLI를 부르지 않고 곧바로 이유를 돌려줘야 한다.
        val useCase = drafts().useCaseFor(directory())

        val result = useCase.draft(
            drafts().stateFor(project, directory()).copy(changes = { PendingChanges(listOf("src/A.kt")) }),
        )

        assertEquals(DraftCommitMessageUseCase.DISABLED, (result as CommitDraftResult.Refused).message)
    }
}
