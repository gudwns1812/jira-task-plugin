package io.github.gudwns1812.jiratask.ui.search

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.gudwns1812.jiratask.domain.jira.JiraIssueDetail
import io.github.gudwns1812.jiratask.domain.jira.JiraStatusCategory
import io.github.gudwns1812.jiratask.domain.jira.SprintTask

/** 검색 창. 저장이 실패해도 사용자가 친 글이 남는지가 핵심이다. */
class JiraSearchDialogTest : BasePlatformTestCase() {

    private fun dialog(projectKey: String? = "S15P11A108") =
        JiraSearchDialog(project, projectKey).also { disposeOnTearDown(it.disposable) }

    private fun found(key: String, summary: String = "회원가입 화면") =
        SprintTask(key, summary, "진행 중", JiraStatusCategory.IN_PROGRESS, inOpenSprint = true)

    private fun detail(key: String = "S15P11A108-81", summary: String = "제목", description: String = "설명") =
        JiraIssueDetail(key, summary, description, "진행 중", "박형준", "Sprint 1")

    fun `test asks for a key search when a key was typed`() {
        val dialog = dialog()
        var asked: JiraSearchQuery? = null
        dialog.onSearch = { asked = it }

        dialog.searchField.text = "S15P11A108-81"
        dialog.search()

        assertEquals(JiraSearchQuery.ByKey("S15P11A108-81"), asked)
    }

    fun `test lists what the search returned`() {
        val dialog = dialog()

        dialog.showResults(listOf(found("S15P11A108-81"), found("S15P11A108-82")))

        assertEquals(2, dialog.resultList.model.size)
        assertEquals("", dialog.statusText())
    }

    fun `test says so when nothing matched`() {
        val dialog = dialog()

        dialog.showResults(emptyList())

        assertEquals(JiraSearchDialog.NO_RESULT, dialog.statusText())
    }

    fun `test shows the failure instead of an empty result`() {
        val dialog = dialog()

        dialog.showProblem("Jira 서버와 통신할 수 없습니다.")

        assertEquals("Jira 서버와 통신할 수 없습니다.", dialog.statusText())
        assertEquals(0, dialog.resultList.model.size)
    }

    fun `test asks to open the issue that was selected`() {
        val dialog = dialog()
        val opened = mutableListOf<String>()
        dialog.onOpen = { opened += it }
        dialog.showResults(listOf(found("S15P11A108-81")))

        dialog.resultList.selectedIndex = 0

        assertEquals(listOf("S15P11A108-81"), opened)
    }

    fun `test fills the fields from the issue it opened`() {
        val dialog = dialog()

        dialog.showDetail(detail(summary = "회원가입 화면", description = "## 설명\n화면을 만든다."))

        assertEquals("회원가입 화면", dialog.summaryField.text)
        assertEquals("## 설명\n화면을 만든다.", dialog.descriptionArea.text)
        assertTrue(dialog.editState() is SearchDetailState.Editing)
    }

    fun `test shows the status, assignee and sprint without letting them be edited`() {
        // 상태와 스프린트는 automation과 계획의 영역이다(RULE.md 6.2).
        val dialog = dialog()

        dialog.showDetail(detail())

        val meta = dialog.metaText()
        listOf("S15P11A108-81", "진행 중", "박형준", "Sprint 1").forEach {
            assertTrue("$it 이 보여야 한다: $meta", meta.contains(it))
        }
    }

    fun `test leaves out the pieces jira did not give`() {
        val dialog = dialog()

        dialog.showDetail(detail().copy(assignee = "", sprint = ""))

        assertEquals("S15P11A108-81  ·  진행 중", dialog.metaText())
    }

    fun `test cannot save until something is edited`() {
        val dialog = dialog()

        dialog.showDetail(detail())
        assertFalse(dialog.isOKActionEnabled)

        dialog.summaryField.text = "새 제목"
        assertTrue(dialog.isOKActionEnabled)
    }

    fun `test refuses to save a blank summary and says why`() {
        val dialog = dialog()
        dialog.showDetail(detail())

        dialog.summaryField.text = "   "

        assertFalse(dialog.isOKActionEnabled)
        assertEquals(IssueEdit.BLANK_SUMMARY, dialog.statusText())
    }

    fun `test sends the edited values when saving`() {
        val dialog = dialog()
        val saved = mutableListOf<IssueEdit>()
        dialog.onSave = { saved += it }
        dialog.showDetail(detail())

        dialog.summaryField.text = "새 제목"
        dialog.descriptionArea.text = "새 설명"
        dialog.doOKAction()

        assertEquals(1, saved.size)
        assertEquals("새 제목", saved.single().summary)
        assertEquals("새 설명", saved.single().description)
        assertEquals("S15P11A108-81", saved.single().original.key)
    }

    fun `test keeps what was typed when the save failed`() {
        // 저장 실패로 사용자가 친 글을 잃게 만들면 안 된다.
        val dialog = dialog()
        dialog.showDetail(detail())
        dialog.summaryField.text = "새 제목"

        dialog.showProblem("저장하지 못했습니다: 409")

        assertEquals("새 제목", dialog.summaryField.text)
        assertTrue("다시 저장할 수 있어야 한다", dialog.isOKActionEnabled)
    }

    fun `test treats a saved edit as the new starting point`() {
        val dialog = dialog()
        dialog.showDetail(detail())
        dialog.summaryField.text = "새 제목"

        dialog.markSaved()

        assertFalse("저장한 뒤에는 다시 저장할 것이 없다", dialog.isOKActionEnabled)
        assertEquals("새 제목", dialog.currentEdit()?.original?.summary)
    }

    fun `test saving does nothing when no issue is open`() {
        val dialog = dialog()
        val saved = mutableListOf<IssueEdit>()
        dialog.onSave = { saved += it }

        dialog.doOKAction()

        assertEmpty(saved)
    }

    fun `test opening another issue replaces what was on screen`() {
        val dialog = dialog()
        dialog.showDetail(detail(key = "S15P11A108-81", summary = "첫 번째"))
        dialog.summaryField.text = "고치는 중"

        dialog.showDetail(detail(key = "S15P11A108-82", summary = "두 번째"))

        assertEquals("두 번째", dialog.summaryField.text)
        assertEquals("S15P11A108-82", dialog.currentEdit()?.original?.key)
        assertFalse(dialog.isOKActionEnabled)
    }

    fun `test searches by text when the project key is unknown`() {
        val dialog = dialog(projectKey = null)
        var asked: JiraSearchQuery? = null
        dialog.onSearch = { asked = it }

        dialog.searchField.text = "81"
        dialog.search()

        assertEquals(JiraSearchQuery.ByText("81"), asked)
    }
}
