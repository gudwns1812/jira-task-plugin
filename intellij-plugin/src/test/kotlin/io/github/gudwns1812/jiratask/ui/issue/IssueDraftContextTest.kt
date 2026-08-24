package io.github.gudwns1812.jiratask.ui.issue

import io.github.gudwns1812.jiratask.domain.jira.JiraIssue
import io.github.gudwns1812.jiratask.domain.jira.JiraNetworkError
import io.github.gudwns1812.jiratask.domain.jira.StoryIssueReader
import io.github.gudwns1812.jiratask.domain.policy.WorkPolicy
import io.github.gudwns1812.jiratask.ui.IssueDraftSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 다이얼로그를 열기 전에 값을 모으는 규칙 — 손으로 쓸 것을 줄이는 핵심이다. */
class IssueDraftContextTest {

    private val settings = IssueDraftSettings("S15P11A108", WorkPolicy.DEFAULT)

    private class FakeStoryReader(
        private val summary: String = "[AUTH-001] 이메일 회원가입",
        private val failure: Exception? = null,
    ) : StoryIssueReader {
        val requested = mutableListOf<String>()

        override fun getIssue(issueKey: String): JiraIssue {
            requested += issueKey
            failure?.let { throw it }
            return JiraIssue(issueKey, "10001", "스토리", "21", "진행 중", summary)
        }

        override fun findTransitionId(issueKey: String, targetStatusId: String): String? = null

        override fun transition(issueKey: String, transitionId: String) = Unit
    }

    private fun reader(
        branch: String?,
        story: FakeStoryReader? = FakeStoryReader(),
        remembered: RememberedInput = RememberedInput(),
    ) = IssueDraftContextReader(settings, remembered, branch, story?.let { { it } })

    @Test
    fun `derives the story key and the feature id from the current branch`() {
        val story = FakeStoryReader()

        val context = reader("feat/100-signup", story).read()

        assertEquals("S15P11A108-100", context.storyKey)
        assertEquals("AUTH-001", context.featureId)
        assertEquals(listOf("S15P11A108-100"), story.requested)
        assertNull(context.lookupNote)
    }

    @Test
    fun `puts the feature id first among the scope choices`() {
        val context = reader("feat/100-signup").read()

        assertEquals("AUTH-001", context.scopeChoices().first())
        assertEquals("AUTH-001", context.defaultScope())
        assertTrue(context.scopeChoices().containsAll(WorkPolicy.DEFAULT.workCategories))
    }

    @Test
    fun `says why it cannot tell the story from a plain branch`() {
        val story = FakeStoryReader()

        val context = reader("main", story).read()

        assertNull(context.storyKey)
        assertNull(context.featureId)
        assertEquals(IssueDraftContextReader.NO_STORY_BRANCH, context.lookupNote)
        assertTrue("스토리를 모르면 Jira를 부르지 않는다", story.requested.isEmpty())
    }

    @Test
    fun `keeps the story key when jira cannot be read`() {
        val context = reader("feat/100-signup", FakeStoryReader(failure = JiraNetworkError())).read()

        assertEquals("S15P11A108-100", context.storyKey)
        assertNull(context.featureId)
        assertEquals(IssueDraftContextReader.lookupFailed("S15P11A108-100"), context.lookupNote)
    }

    @Test
    fun `keeps working without jira connection settings`() {
        val context = reader("feat/100-signup", story = null).read()

        assertEquals("S15P11A108-100", context.storyKey)
        assertEquals(IssueDraftContextReader.NO_CONNECTION, context.lookupNote)
    }

    @Test
    fun `notes a story title that does not carry a feature id`() {
        val context = reader("feat/100-signup", FakeStoryReader(summary = "회원가입 개선")).read()

        assertNull(context.featureId)
        assertEquals(IssueDraftContextReader.noFeatureId("S15P11A108-100"), context.lookupNote)
    }

    @Test
    fun `falls back to the remembered values`() {
        val remembered = RememberedInput(scope = "PLAN", area = "BE", storyPoints = 5, epicKey = "S15P11A108-9")

        val context = reader("main", remembered = remembered).read()

        assertEquals("PLAN", context.defaultScope())
        assertEquals("BE", context.defaultArea())
        assertEquals(5, context.defaultStoryPoints())
        assertEquals("S15P11A108-9", context.epicKey)
    }

    @Test
    fun `defaults to the policy when nothing is remembered`() {
        val context = reader("main", story = null).read()

        assertEquals("PLAN", context.defaultScope())
        assertEquals("FE", context.defaultArea())
        assertEquals(1, context.defaultStoryPoints())
    }

    @Test
    fun `composes titles in the shape the rules require`() {
        assertEquals("[AUTH-001 | FE] 회원가입 폼", TaskTitles.compose("AUTH-001", "FE", "회원가입 폼"))
        assertEquals("[PLAN | BE] 스프린트 계획", TaskTitles.compose("PLAN", "BE", "스프린트 계획"))
        assertEquals("[PLAN] 스프린트 계획", TaskTitles.compose("PLAN", null, "스프린트 계획"))
        assertEquals("[PLAN] 스프린트 계획", TaskTitles.compose(" PLAN ", "  ", "  스프린트 계획  "))
    }

    @Test
    fun `reads the feature id only from a story shaped title`() {
        assertEquals("AUTH-001", TaskTitles.featureIdOf("[AUTH-001] 이메일 회원가입"))
        assertNull(TaskTitles.featureIdOf("[AUTH-001 | FE] 회원가입 폼"))
        assertNull(TaskTitles.featureIdOf("회원가입"))
        assertNull(TaskTitles.featureIdOf(null))
    }
}
