package io.github.gudwns1812.jiratask.domain.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkPolicyTest {

    @Test
    fun `default policy matches RULE md defaults`() {
        assertEquals(listOf("FE", "BE", "AI", "QA", "INFRA"), WorkPolicy.DEFAULT.areas)
        assertEquals(
            listOf("PLAN", "UX", "DESIGN", "TECH", "INFRA", "DOCS", "QA", "PT"),
            WorkPolicy.DEFAULT.workCategories,
        )
        assertEquals(listOf(1, 2, 3, 5, 8), WorkPolicy.DEFAULT.storyPoints)
        assertEquals("Infra", WorkPolicy.DEFAULT.labels.areas["INFRA"])
    }

    @Test
    fun `isArea returns true for known area`() {
        assertTrue(WorkPolicy.DEFAULT.isArea("FE"))
    }

    @Test
    fun `isArea returns false for unknown area`() {
        assertFalse(WorkPolicy.DEFAULT.isArea("Frontend"))
    }

    @Test
    fun `isWorkCategory returns true for known category`() {
        assertTrue(WorkPolicy.DEFAULT.isWorkCategory("PLAN"))
    }

    @Test
    fun `isWorkCategory returns false for unknown category`() {
        assertFalse(WorkPolicy.DEFAULT.isWorkCategory("UNKNOWN"))
    }

    @Test
    fun `labelForArea returns mapped label when present`() {
        assertEquals("Infra", WorkPolicy.DEFAULT.labelForArea("INFRA"))
    }

    @Test
    fun `labelForArea falls back to the area code when no mapping exists`() {
        assertEquals("FE", WorkPolicy.DEFAULT.labelForArea("FE"))
    }

    @Test
    fun `labelForCategory returns mapped label when present`() {
        assertEquals("기획", WorkPolicy.DEFAULT.labelForCategory("PLAN"))
    }

    @Test
    fun `labelForCategory returns null when category has no configured label`() {
        assertNull(WorkPolicy.DEFAULT.labelForCategory("TECH"))
    }

    @Test
    fun `custom policy overrides defaults independently`() {
        val custom = WorkPolicy(
            areas = listOf("FE"),
            workCategories = listOf("PLAN"),
            storyPoints = listOf(1),
            labels = WorkPolicy.Labels(areas = emptyMap(), categories = emptyMap()),
        )

        assertTrue(custom.isArea("FE"))
        assertFalse(custom.isArea("BE"))
        assertEquals("FE", custom.labelForArea("FE"))
        assertNull(custom.labelForCategory("PLAN"))
    }
}
