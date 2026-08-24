package io.github.gudwns1812.jiratask.ui

import com.intellij.notification.NotificationType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** 상태 변경 통지와, 알림과 함께 통지하는 도우미. */
class JiraTaskStateTest : BasePlatformTestCase() {

    private fun subscribe(): MutableList<String> {
        val received = mutableListOf<String>()
        project.messageBus.connect(testRootDisposable)
            .subscribe(JiraTaskStateListener.TOPIC, JiraTaskStateListener { received += "changed" })
        return received
    }

    fun `test delivers a state change to subscribers`() {
        val received = subscribe()

        JiraTaskStateListener.publish(project)

        assertEquals(listOf("changed"), received)
    }

    fun `test notifies and announces the change together`() {
        val received = subscribe()

        notifyAndRefresh(project, NotificationType.INFORMATION, "커밋했습니다: S15P11A108-257 회원가입")

        assertEquals("알림만 띄우고 갱신을 잊으면 화면이 낡은 값을 보여준다", listOf("changed"), received)
    }

    fun `test survives a null project`() {
        val received = subscribe()

        notifyAndRefresh(null, NotificationType.ERROR, "설정 실패")

        assertEmpty(received)
    }

    fun `test announces application level changes to open projects`() {
        val received = subscribe()

        JiraTaskStateListener.publishToAllProjects()

        assertEquals(listOf("changed"), received)
    }
}
