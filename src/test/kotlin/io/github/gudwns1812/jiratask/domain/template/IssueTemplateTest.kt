package io.github.gudwns1812.jiratask.domain.template

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueTemplateTest {

    @Test
    fun `returns RULE md templates for story and task`() {
        assertTrue(IssueTemplates.get("story")!!.contains("feat/100-signup"))
        assertTrue(IssueTemplates.get("task")!!.contains("## 결과물"))
    }

    @Test
    fun `returns null for unsupported issue types`() {
        assertNull(IssueTemplates.get("epic"))
        assertNull(IssueTemplates.get("TASK"))
    }
}
