package io.github.gudwns1812.jiratask.ui.task

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.gudwns1812.jiratask.application.CredentialStore
import io.github.gudwns1812.jiratask.domain.jira.JiraNetworkError
import io.github.gudwns1812.jiratask.domain.jira.JiraStatusCategory
import io.github.gudwns1812.jiratask.domain.jira.SprintTask
import io.github.gudwns1812.jiratask.domain.jira.SprintTaskReader
import io.github.gudwns1812.jiratask.infrastructure.JiraTaskConfigStorage
import io.github.gudwns1812.jiratask.infrastructure.PolicyConfigRepository
import io.github.gudwns1812.jiratask.ui.JiraTaskSettings

/** 커밋 창이 반복해서 묻는 값이라 캐시가 본체다. 무엇을 캐시하고 무엇을 캐시하지 않는지를 본다. */
class SprintTasksTest : BasePlatformTestCase() {

    private object AnyToken : CredentialStore {
        override fun save(baseUrl: String, email: String, apiToken: String) = Unit
        override fun get(baseUrl: String, email: String): String = "token"
    }

    private class FakeReader(
        var tasks: List<SprintTask> = emptyList(),
        var single: SprintTask? = null,
        var failure: Exception? = null,
    ) : SprintTaskReader {
        var listCalls = 0
        var singleCalls = 0

        override fun currentSprintTasks(projectKey: String, taskIssueTypeId: String): List<SprintTask> {
            listCalls++
            failure?.let { throw it }
            return tasks
        }

        override fun task(issueKey: String): SprintTask? {
            singleCalls++
            failure?.let { throw it }
            return single
        }

        override fun searchByText(projectKey: String, text: String, limit: Int): List<SprintTask> = emptyList()
    }

    private fun task(key: String, category: JiraStatusCategory = JiraStatusCategory.TO_DO, inSprint: Boolean = true) =
        SprintTask(key, "제목", "상태", category, inSprint)

    private fun settings(configured: Boolean = true): JiraTaskSettings {
        val repository = PolicyConfigRepository(JiraTaskConfigStorage())
        if (configured) {
            repository.saveConfig(
                mapOf(
                    "projectKey" to "S15P11A108",
                    "jira" to mapOf(
                        "baseUrl" to "https://team.atlassian.net",
                        "email" to "gudwns1423@gmail.com",
                        "issueTypes" to mapOf("task" to mapOf("id" to "10002", "name" to "작업")),
                        "statuses" to mapOf("story" to mapOf("start" to mapOf("id" to "21", "name" to "진행 중"))),
                    ),
                ),
            )
        }
        return JiraTaskSettings(repository, AnyToken) { null }
    }

    private fun sprintTasks() = SprintTasks.getInstance(project).also { it.invalidate() }

    fun `test reads the sprint tasks and reports them`() {
        val reader = FakeReader(tasks = listOf(task("S15P11A108-257")))

        val load = sprintTasks().current(settings(), { reader }) { 0 }

        assertEquals(listOf("S15P11A108-257"), (load as SprintTaskLoad.Loaded).tasks.map { it.key })
    }

    fun `test does not ask jira again inside the ttl`() {
        val reader = FakeReader(tasks = listOf(task("S15P11A108-1")))
        val cache = sprintTasks()

        cache.current(settings(), { reader }) { 0 }
        cache.current(settings(), { reader }) { SprintTasks.TTL_MILLIS - 1 }

        assertEquals("캐시가 살아 있으면 한 번만 묻는다", 1, reader.listCalls)
    }

    fun `test asks again once the ttl has passed`() {
        val reader = FakeReader(tasks = listOf(task("S15P11A108-1")))
        val cache = sprintTasks()

        cache.current(settings(), { reader }) { 0 }
        cache.current(settings(), { reader }) { SprintTasks.TTL_MILLIS }

        assertEquals(2, reader.listCalls)
    }

    fun `test reload ignores a live cache`() {
        val reader = FakeReader(tasks = listOf(task("S15P11A108-1")))
        val cache = sprintTasks()

        cache.current(settings(), { reader }) { 0 }
        cache.reload(settings(), { reader }) { 0 }

        assertEquals(2, reader.listCalls)
    }

    fun `test never caches a failure`() {
        // 잠깐 끊긴 네트워크 때문에 TTL 내내 실패로 남으면 안 된다.
        val reader = FakeReader(failure = JiraNetworkError())
        val cache = sprintTasks()

        val first = cache.current(settings(), { reader }) { 0 }
        cache.current(settings(), { reader }) { 1 }

        assertTrue(first is SprintTaskLoad.Failed)
        assertEquals("실패한 뒤에는 다시 시도한다", 2, reader.listCalls)
    }

    fun `test says what is missing instead of asking jira without settings`() {
        val reader = FakeReader()

        val load = sprintTasks().current(settings(configured = false), { reader }) { 0 }

        assertTrue(load is SprintTaskLoad.NotConfigured)
        assertEquals("설정이 없으면 Jira를 부르지 않는다", 0, reader.listCalls)
    }

    fun `test finds a cached task without asking jira again`() {
        val reader = FakeReader(tasks = listOf(task("S15P11A108-257")))
        val cache = sprintTasks()
        cache.current(settings(), { reader }) { 0 }

        val found = cache.find("S15P11A108-257", settings()) { reader }

        assertEquals("S15P11A108-257", (found as TaskAnswer.Known).task.key)
        assertEquals("목록에 있으면 되묻지 않는다", 0, reader.singleCalls)
    }

    fun `test asks jira for a key the cached list does not hold`() {
        // 목록은 담당자로 좁힌 것이라 남의 작업 키는 여기 없다(RULE.md 5.2).
        val teammates = task("S15P11A108-999")
        val reader = FakeReader(tasks = listOf(task("S15P11A108-257")), single = teammates)
        val cache = sprintTasks()
        cache.current(settings(), { reader }) { 0 }

        val found = cache.find("S15P11A108-999", settings()) { reader }

        assertEquals("S15P11A108-999", (found as TaskAnswer.Known).task.key)
        assertEquals(1, reader.singleCalls)
    }

    fun `test remembers a key it already asked about`() {
        val reader = FakeReader(single = task("S15P11A108-999"))
        val cache = sprintTasks()

        cache.find("S15P11A108-999", settings()) { reader }
        cache.find("S15P11A108-999", settings()) { reader }

        assertEquals(1, reader.singleCalls)
    }

    fun `test matches a cached key regardless of letter case`() {
        val reader = FakeReader(tasks = listOf(task("S15P11A108-257")))
        val cache = sprintTasks()
        cache.current(settings(), { reader }) { 0 }

        assertTrue(cache.find("s15p11a108-257", settings()) { reader } is TaskAnswer.Known)
        assertEquals(0, reader.singleCalls)
    }

    fun `test separates a key it could not ask about from one that does not exist`() {
        // 커밋 가드는 없는 키에는 경고하고 물어보지 못한 키에는 침묵한다(RULE.md 5.2).
        val offline = FakeReader(failure = JiraNetworkError())
        val missing = FakeReader(single = null)

        assertEquals(TaskAnswer.Unanswered, sprintTasks().find("S15P11A108-1", settings()) { offline })
        assertEquals(TaskAnswer.NotFound, sprintTasks().find("S15P11A108-9999", settings()) { missing })
    }

    fun `test cannot answer without settings instead of claiming the key is missing`() {
        val reader = FakeReader(single = null)

        assertEquals(TaskAnswer.Unanswered, sprintTasks().find("S15P11A108-1", settings(configured = false)) { reader })
        assertEquals("설정이 없으면 Jira를 부르지 않는다", 0, reader.singleCalls)
    }

    fun `test the cache-only lookup never touches the network`() {
        // 커밋 직전 검사는 EDT에서 불린다. 여기서 네트워크를 타면 커밋 창이 멈춘다.
        val reader = FakeReader(tasks = listOf(task("S15P11A108-257")), single = task("S15P11A108-999"))
        val cache = sprintTasks()

        assertEquals(TaskAnswer.Unanswered, cache.findCached("S15P11A108-257"))

        cache.current(settings(), { reader }) { 0 }

        assertTrue(cache.findCached("S15P11A108-257") is TaskAnswer.Known)
        assertEquals("캐시에 없는 키는 모른다고 답한다", TaskAnswer.Unanswered, cache.findCached("S15P11A108-999"))
        assertEquals(0, reader.singleCalls)
    }

    fun `test invalidate drops both the list and the single keys`() {
        val reader = FakeReader(tasks = listOf(task("S15P11A108-1")), single = task("S15P11A108-999"))
        val cache = sprintTasks()
        cache.current(settings(), { reader }) { 0 }
        cache.find("S15P11A108-999", settings()) { reader }

        cache.invalidate()
        cache.current(settings(), { reader }) { 0 }
        cache.find("S15P11A108-999", settings()) { reader }

        assertEquals(2, reader.listCalls)
        assertEquals(2, reader.singleCalls)
    }

    fun `test keeps a finished task in the list`() {
        val reader = FakeReader(tasks = listOf(task("S15P11A108-1", JiraStatusCategory.DONE)))

        val load = sprintTasks().current(settings(), { reader }) { 0 } as SprintTaskLoad.Loaded

        assertTrue("끝난 작업도 캐시에 담는다", load.tasks.single().statusCategory.isDone)
    }
}
