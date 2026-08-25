package io.github.gudwns1812.jiratask.ui.task

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import io.github.gudwns1812.jiratask.domain.jira.SprintTask
import io.github.gudwns1812.jiratask.domain.jira.SprintTaskReader
import io.github.gudwns1812.jiratask.infrastructure.JiraClient
import io.github.gudwns1812.jiratask.ui.JiraTaskSettings
import io.github.gudwns1812.jiratask.ui.SettingsLookup
import io.github.gudwns1812.jiratask.ui.TaskTransitionSettings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicReference

/** 스프린트 작업을 읽어 본 결과. 실패를 값으로 돌려준다 — 커밋 창은 예외를 감당할 수 없다. */
sealed interface SprintTaskLoad {
    data class Loaded(val tasks: List<SprintTask>) : SprintTaskLoad

    /** 설정이 없어 물어보지도 못했다. */
    data class NotConfigured(val message: String) : SprintTaskLoad

    /** 물어봤지만 Jira가 답하지 못했다. */
    data class Failed(val message: String) : SprintTaskLoad
}

/**
 * 스프린트 작업 조회의 창구. 캐시가 본체다.
 *
 * 커밋 창은 열릴 때마다, 커밋할 때마다 이 값을 묻는다. 그때마다 네트워크를 치면 IDE가 느려지므로
 * 짧은 TTL 동안 결과를 들고 있는다. 실패는 캐시하지 않는다 — 잠깐 끊긴 네트워크 때문에
 * TTL 내내 실패한 상태로 남으면 안 된다.
 *
 * **모든 호출은 백그라운드 스레드에서 해야 한다.** EDT에서 부르면 IDE가 멈춘다.
 */
@Service(Service.Level.PROJECT)
class SprintTasks(private val project: Project) {

    private val cached = AtomicReference<Snapshot?>(null)

    /** 키 하나를 조회한 결과. 커밋 가드가 목록에 없는 키를 되물을 때 쓴다. */
    private val singles: ConcurrentMap<String, SprintTask> = ConcurrentHashMap()

    private data class Snapshot(val tasks: List<SprintTask>, val readAtMillis: Long)

    /** 캐시가 살아 있으면 그대로, 아니면 Jira에 묻는다. */
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

    /** 캐시를 버리고 다시 읽는다. 사용자가 새로 고침을 눌렀을 때. */
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
            val tasks = createReader(value).currentSprintTasks(value.projectKey, value.taskIssueTypeId)
            cached.set(Snapshot(tasks, nowMillis()))
            singles.clear()
            SprintTaskLoad.Loaded(tasks)
        } catch (error: Exception) {
            // 실패는 캐시하지 않는다. 다음 호출이 다시 시도한다.
            SprintTaskLoad.Failed(error.message ?: "Jira 작업 목록을 읽지 못했습니다.")
        }
    }

    /**
     * 이슈 키 하나를 확인한다. 캐시된 목록에 있으면 그대로 쓰고, 없으면 Jira에 되묻는다.
     *
     * 목록은 담당자로 좁힌 것이라 남의 작업 키는 여기에 없다(대신 처리, 페어 작업).
     * 캐시에 없다는 것만으로 "스프린트 밖"이라고 볼 수 없는 이유다(RULE.md 5.2).
     * 확인할 수 없으면 null — 호출한 쪽이 "모른다"와 "위반"을 구분해야 한다.
     */
    fun find(
        issueKey: String,
        settings: JiraTaskSettings = JiraTaskSettings(),
        createReader: (TaskTransitionSettings) -> SprintTaskReader = ::jiraClientFor,
    ): SprintTask? {
        cached.get()?.tasks?.firstOrNull { it.key.equals(issueKey, ignoreCase = true) }?.let { return it }
        singles[issueKey.uppercase()]?.let { return it }

        val ready = settings.forTaskTransition() as? SettingsLookup.Ready ?: return null
        return try {
            createReader(ready.value).task(issueKey)?.also { singles[issueKey.uppercase()] = it }
        } catch (error: Exception) {
            null
        }
    }

    /** 캐시를 비운다. 작업을 시작했거나 설정이 바뀌었을 때. */
    fun invalidate() {
        cached.set(null)
        singles.clear()
    }

    private fun jiraClientFor(settings: TaskTransitionSettings): SprintTaskReader =
        JiraClient(settings.baseUrl, settings.email, settings.apiToken)

    companion object {
        /** 커밋 창을 여닫는 동안은 다시 묻지 않을 만큼 짧게. */
        const val TTL_MILLIS: Long = 60_000

        fun getInstance(project: Project): SprintTasks = project.getService(SprintTasks::class.java)
    }
}
