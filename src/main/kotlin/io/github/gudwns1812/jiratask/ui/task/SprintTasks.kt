package io.github.gudwns1812.jiratask.ui.task

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import io.github.gudwns1812.jiratask.domain.jira.EmptySprintReason
import io.github.gudwns1812.jiratask.domain.jira.SprintTask
import io.github.gudwns1812.jiratask.domain.jira.SprintTaskReader
import io.github.gudwns1812.jiratask.infrastructure.JiraClient
import io.github.gudwns1812.jiratask.ui.JiraTaskSettings
import io.github.gudwns1812.jiratask.ui.SettingsLookup
import io.github.gudwns1812.jiratask.ui.TaskTransitionSettings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicReference

sealed interface TaskAnswer {
    data class Known(val task: SprintTask) : TaskAnswer

    data object NotFound : TaskAnswer

    data object Unanswered : TaskAnswer
}

sealed interface SprintTaskLoad {
    data class Loaded(
        val tasks: List<SprintTask>,
        val reason: EmptySprintReason? = null,
        val otherAssigneeCount: Int = 0,
    ) : SprintTaskLoad

    data class NotConfigured(val message: String) : SprintTaskLoad

    data class Failed(val message: String) : SprintTaskLoad
}

@Service(Service.Level.PROJECT)
class SprintTasks(private val project: Project) {

    private val cached = AtomicReference<Snapshot?>(null)

    private val singles: ConcurrentMap<String, SprintTask> = ConcurrentHashMap()

    private data class Snapshot(val tasks: List<SprintTask>, val readAtMillis: Long)

    fun current(
        settings: JiraTaskSettings = JiraTaskSettings(),
        createReader: (TaskTransitionSettings) -> SprintTaskReader = ::jiraClientFor,
        nowMillis: () -> Long = System::currentTimeMillis,
    ): SprintTaskLoad {
        cached.get()?.takeIf { nowMillis() - it.readAtMillis < TTL_MILLIS }?.let {
            return SprintTaskLoad.Loaded(it.tasks)
        }
        return reload(settings, createReader, nowMillis)
    }

    fun reload(
        settings: JiraTaskSettings = JiraTaskSettings(),
        createReader: (TaskTransitionSettings) -> SprintTaskReader = ::jiraClientFor,
        nowMillis: () -> Long = System::currentTimeMillis,
    ): SprintTaskLoad {
        val ready = settings.forTaskTransition()
        if (ready is SettingsLookup.Missing) {
            return SprintTaskLoad.NotConfigured(ready.message)
        }

        val value = (ready as SettingsLookup.Ready).value
        return try {
            val reader = createReader(value)
            val tasks = reader.currentSprintTasks(value.projectKey, value.taskIssueTypeId)
            cached.set(Snapshot(tasks, nowMillis()))
            singles.clear()
            SprintTaskLoad.Loaded(
                tasks = tasks,
                reason = if (tasks.isEmpty()) whyEmpty(reader, value) else null,
                otherAssigneeCount = othersInSprint(reader, value, tasks.size),
            )
        } catch (error: Exception) {
            SprintTaskLoad.Failed(error.message ?: "Jira 작업 목록을 읽지 못했습니다.")
        }
    }

    fun find(
        issueKey: String,
        settings: JiraTaskSettings = JiraTaskSettings(),
        createReader: (TaskTransitionSettings) -> SprintTaskReader = ::jiraClientFor,
    ): TaskAnswer {
        cached.get()?.tasks?.firstOrNull { it.key.equals(issueKey, ignoreCase = true) }
            ?.let { return TaskAnswer.Known(it) }
        singles[issueKey.uppercase()]?.let { return TaskAnswer.Known(it) }

        val ready = settings.forTaskTransition() as? SettingsLookup.Ready ?: return TaskAnswer.Unanswered
        return try {
            val task = createReader(ready.value).task(issueKey)
                ?: return TaskAnswer.NotFound
            singles[issueKey.uppercase()] = task
            TaskAnswer.Known(task)
        } catch (error: Exception) {
            TaskAnswer.Unanswered
        }
    }

    private fun othersInSprint(reader: SprintTaskReader, settings: TaskTransitionSettings, mine: Int): Int = try {
        (reader.countTasks(settings.projectKey, settings.taskIssueTypeId, false, true) - mine).coerceAtLeast(0)
    } catch (error: Exception) {
        0
    }

    private fun whyEmpty(reader: SprintTaskReader, settings: TaskTransitionSettings): EmptySprintReason = try {
        EmptySprintReason.of(
            anyTask = reader.countTasks(settings.projectKey, settings.taskIssueTypeId, false, false),
            mineAnywhere = reader.countTasks(settings.projectKey, settings.taskIssueTypeId, true, false),
            anyInSprint = reader.countTasks(settings.projectKey, settings.taskIssueTypeId, false, true),
        )
    } catch (error: Exception) {
        EmptySprintReason.UNKNOWN
    }

    fun findCached(issueKey: String): TaskAnswer {
        cached.get()?.tasks?.firstOrNull { it.key.equals(issueKey, ignoreCase = true) }
            ?.let { return TaskAnswer.Known(it) }
        return singles[issueKey.uppercase()]?.let { TaskAnswer.Known(it) } ?: TaskAnswer.Unanswered
    }

    fun invalidate() {
        cached.set(null)
        singles.clear()
    }

    private fun jiraClientFor(settings: TaskTransitionSettings): SprintTaskReader =
        JiraClient(settings.baseUrl, settings.email, settings.apiToken)

    companion object {
        const val TTL_MILLIS: Long = 60_000

        fun getInstance(project: Project): SprintTasks = project.getService(SprintTasks::class.java)
    }
}
