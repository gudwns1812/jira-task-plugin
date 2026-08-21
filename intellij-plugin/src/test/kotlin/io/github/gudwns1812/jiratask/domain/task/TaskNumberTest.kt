package io.github.gudwns1812.jiratask.domain.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TaskNumberTest {

    @Test
    fun `builds jira issue key from project key and task number`() {
        assertEquals("S15P11A108-105", TaskNumbers.createTaskKey("S15P11A108", "105"))
    }

    @Test
    fun `accepts a valid task number`() {
        TaskNumbers.validate("1")
    }

    @Test
    fun `rejects invalid task numbers for both validate and createTaskKey`() {
        for (taskNumber in listOf("0", "-1", "105a", " 105", "")) {
            assertThrows(TaskNumberError::class.java) { TaskNumbers.validate(taskNumber) }
            assertThrows(TaskNumberError::class.java) { TaskNumbers.createTaskKey("S15P11A108", taskNumber) }
        }
    }
}
