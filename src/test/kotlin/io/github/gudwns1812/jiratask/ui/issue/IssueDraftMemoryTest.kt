package io.github.gudwns1812.jiratask.ui.issue

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** 지난번 입력 기억. 매번 담당자·우선순위·스프린트를 다시 타이핑하지 않게 하는 부분이다. */
class IssueDraftMemoryTest : BasePlatformTestCase() {

    fun `test remembers nothing at first`() {
        val memory = IssueDraftMemory()

        assertEquals(RememberedInput(), memory.remembered())
    }

    fun `test remembers the values it is given`() {
        val memory = IssueDraftMemory()

        memory.remember(
            RememberedInput(
                scope = "AUTH-001",
                area = "FE",
                assignee = "박형준",
                priority = "High",
                storyPoints = 3,
                sprint = "Sprint 1",
                dueDate = "2026-08-31",
                epicKey = "S15P11A108-1",
            ),
        )

        val remembered = memory.remembered()
        assertEquals("AUTH-001", remembered.scope)
        assertEquals("FE", remembered.area)
        assertEquals("박형준", remembered.assignee)
        assertEquals("High", remembered.priority)
        assertEquals(3, remembered.storyPoints)
        assertEquals("Sprint 1", remembered.sprint)
        assertEquals("2026-08-31", remembered.dueDate)
        assertEquals("S15P11A108-1", remembered.epicKey)
    }

    fun `test keeps the previous value when a field comes back empty`() {
        val memory = IssueDraftMemory()
        memory.remember(RememberedInput(assignee = "박형준", sprint = "Sprint 1"))

        memory.remember(RememberedInput(assignee = "  ", sprint = null, priority = "Medium"))

        assertEquals("빈 값으로 기억을 지우지 않는다", "박형준", memory.remembered().assignee)
        assertEquals("Sprint 1", memory.remembered().sprint)
        assertEquals("Medium", memory.remembered().priority)
    }

    fun `test trims what it remembers`() {
        val memory = IssueDraftMemory()

        memory.remember(RememberedInput(assignee = "  박형준  "))

        assertEquals("박형준", memory.remembered().assignee)
    }

    fun `test survives a save and load round trip`() {
        val memory = IssueDraftMemory()
        memory.remember(RememberedInput(assignee = "박형준", storyPoints = 5))

        val reloaded = IssueDraftMemory().apply { loadState(memory.state) }

        assertEquals("박형준", reloaded.remembered().assignee)
        assertEquals(5, reloaded.remembered().storyPoints)
    }

    fun `test is available as a project service`() {
        val memory = IssueDraftMemory.getInstance(project)

        memory.remember(RememberedInput(sprint = "Sprint 9"))

        assertEquals("Sprint 9", IssueDraftMemory.getInstance(project).remembered().sprint)
    }
}
