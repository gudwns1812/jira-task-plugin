package io.github.gudwns1812.jiratask.ui.issue

import io.github.gudwns1812.jiratask.domain.jira.JiraNetworkError
import io.github.gudwns1812.jiratask.domain.jira.JiraDraftReader
import io.github.gudwns1812.jiratask.domain.jira.JiraStoryContext
import io.github.gudwns1812.jiratask.domain.policy.WorkPolicy
import io.github.gudwns1812.jiratask.ui.IssueDraftSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 다이얼로그를 열기 전에 값을 모으는 규칙 — 손으로 쓸 것을 줄이는 핵심이다. */
class IssueDraftContextTest {

    private val settings = IssueDraftSettings("S15P11A108", WorkPolicy.DEFAULT)

    private class FakeDraftReader(
        private val story: JiraStoryContext = JiraStoryContext("[AUTH-001] 이메일 회원가입", "S15P11A108-1", "Sprint 7"),
        private val failure: Exception? = null,
        private val user: String? = "박형준",
        private val priorities: List<String> = listOf("Highest", "High", "Medium", "Low", "Lowest"),
    ) : JiraDraftReader {
        val requested = mutableListOf<String>()

        override fun currentUserName(): String? = user

        override fun listPriorities(): List<String> = priorities

        override fun storyContext(storyKey: String): JiraStoryContext {
            requested += storyKey
            failure?.let { throw it }
            return story
        }
    }

    private fun reader(
        branch: String?,
        jira: FakeDraftReader? = FakeDraftReader(),
        remembered: RememberedInput = RememberedInput(),
    ) = IssueDraftContextReader(settings, remembered, branch, jira?.let { { it } })

    @Test
    fun `derives the story key and the feature id from the current branch`() {
        val story = FakeDraftReader()

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
        val story = FakeDraftReader()

        val context = reader("main", story).read()

        assertNull(context.storyKey)
        assertNull(context.featureId)
        assertEquals(IssueDraftContextReader.NO_STORY_BRANCH, context.lookupNote)
        assertTrue("스토리를 모르면 Jira를 부르지 않는다", story.requested.isEmpty())
    }

    @Test
    fun `keeps the story key when jira cannot be read`() {
        val context = reader("feat/100-signup", FakeDraftReader(failure = JiraNetworkError())).read()

        assertEquals("S15P11A108-100", context.storyKey)
        assertNull(context.featureId)
        assertEquals(IssueDraftContextReader.lookupFailed("S15P11A108-100"), context.lookupNote)
    }

    @Test
    fun `keeps working without jira connection settings`() {
        val context = reader("feat/100-signup", jira = null).read()

        assertEquals("S15P11A108-100", context.storyKey)
        assertEquals(IssueDraftContextReader.NO_CONNECTION, context.lookupNote)
    }

    @Test
    fun `notes a story title that does not carry a feature id`() {
        val context = reader("feat/100-signup", FakeDraftReader(JiraStoryContext(summary = "회원가입 개선"))).read()

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
        val context = reader("main", jira = null).read()

        assertEquals("PLAN", context.defaultScope())
        assertEquals("FE", context.defaultArea())
        assertEquals(1, context.defaultStoryPoints())
    }

    @Test
    fun `takes the epic and the sprint from the story`() {
        val context = reader("feat/100-signup").read()

        assertEquals("작업은 스토리와 같은 에픽에 속한다", "S15P11A108-1", context.epicKey)
        assertEquals("스프린트도 스토리에서 물려받는다", "Sprint 7", context.sprint)
        assertEquals("Sprint 7", context.defaultSprint())
    }

    @Test
    fun `assigns the issue to me by default`() {
        val context = reader("feat/100-signup").read()

        assertEquals("박형준", context.currentUser)
        assertEquals("박형준", context.defaultAssignee())
    }

    @Test
    fun `lets the remembered assignee win over me`() {
        val context = reader("feat/100-signup", remembered = RememberedInput(assignee = "다른 사람")).read()

        assertEquals("다른 사람", context.defaultAssignee())
    }

    @Test
    fun `defaults the priority to the lowest sensible one`() {
        val context = reader("feat/100-signup").read()

        assertEquals(listOf("Highest", "High", "Medium", "Low", "Lowest"), context.priorities)
        assertEquals("Low", context.defaultPriority())
    }

    @Test
    fun `uses whatever priorities this jira defines`() {
        val context = reader("feat/100-signup", FakeDraftReader(priorities = listOf("긴급", "보통"))).read()

        assertEquals(listOf("긴급", "보통"), context.priorities)
        assertEquals("Low가 없으면 가장 낮은 쪽을 고른다", "보통", context.defaultPriority())
    }

    @Test
    fun `falls back to the standard priorities when jira says nothing`() {
        val context = reader("feat/100-signup", FakeDraftReader(priorities = emptyList())).read()

        assertEquals(IssueDraftContext.DEFAULT_PRIORITIES, context.priorities)
        assertEquals("Low", context.defaultPriority())
    }

    @Test
    fun `keeps the remembered epic when the story has none`() {
        val context = reader(
            "feat/100-signup",
            FakeDraftReader(JiraStoryContext("[AUTH-001] 이메일 회원가입", epicKey = null)),
            remembered = RememberedInput(epicKey = "S15P11A108-9"),
        ).read()

        assertEquals("S15P11A108-9", context.epicKey)
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
