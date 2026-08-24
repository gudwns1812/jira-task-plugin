package io.github.gudwns1812.jiratask.ui.issue

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.gudwns1812.jiratask.domain.policy.WorkPolicy

/**
 * 다이얼로그와 폼의 실제 위젯 동작. 작업 입력은 간단 모드(구분·영역·요약)로 줄었고,
 * 나머지 값은 컨텍스트가 채워 "고급"에 들어간다.
 */
class CreateIssueDraftDialogTest : BasePlatformTestCase() {

    private fun context(
        featureId: String? = "AUTH-001",
        remembered: RememberedInput = RememberedInput(
            assignee = "박형준",
            priority = "High",
            storyPoints = 3,
            sprint = "Sprint 1",
            dueDate = "2026-08-31",
        ),
        storyKey: String? = "S15P11A108-100",
        epicKey: String? = "S15P11A108-1",
        branch: String? = "feat/100-signup",
        lookupNote: String? = null,
    ) = IssueDraftContext(
        projectKey = "S15P11A108",
        policy = WorkPolicy.DEFAULT,
        storyKey = storyKey,
        epicKey = epicKey,
        featureId = featureId,
        branch = branch,
        remembered = remembered,
        lookupNote = lookupNote,
    )

    private fun withDialog(
        context: IssueDraftContext = context(),
        onRemember: (RememberedInput) -> Unit = {},
        block: (CreateIssueDraftDialog) -> Unit,
    ) {
        val dialog = CreateIssueDraftDialog(project, context, onRemember)
        try {
            block(dialog)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    fun `test fills everything it can so only the summary is left to type`() {
        withDialog { dialog ->
            val form = dialog.form
            form.selectType("task")

            assertEquals("S15P11A108-1", form.epicKeyField.text)
            assertEquals("S15P11A108-100", form.storyKeyField.text)
            assertEquals("박형준", form.assigneeField.text)
            assertEquals("High", form.priorityField.text)
            assertEquals(3, form.storyPointsComboBox.selectedItem)
            assertEquals("Sprint 1", form.sprintField.text)
            assertEquals("2026-08-31", form.dueDateField.text)
            assertEquals("브랜치에서 알아낸 기능 ID가 기본 구분이다", "AUTH-001", form.scopeComboBox.selectedItem)
        }
    }

    fun `test composes the rule shaped title from the picks and the summary`() {
        withDialog { dialog ->
            val form = dialog.form
            form.selectType("task")
            form.areaComboBox.selectedItem = "FE"
            form.summaryField.text = "회원가입 폼"

            assertEquals("[AUTH-001 | FE] 회원가입 폼", form.composedTitle())
            assertEquals("[AUTH-001 | FE] 회원가입 폼", form.titlePreviewLabel.text)
        }
    }

    fun `test only needs the summary and the deliverables to become valid`() {
        withDialog { dialog ->
            val form = dialog.form
            form.selectType("task")
            form.areaComboBox.selectedItem = "FE"

            assertFalse(dialog.isOKActionEnabled)

            form.summaryField.text = "회원가입 폼"
            form.descriptionArea.text = "이메일 회원가입 폼을 만든다."
            form.deliverablesArea.text = "회원가입 화면"

            assertEquals(emptyList<String>(), dialog.currentView().errors)
            assertTrue(dialog.isOKActionEnabled)
        }
    }

    fun `test builds a common task title from a work category without an area`() {
        withDialog { dialog ->
            val form = dialog.form
            form.selectType("task")
            form.scopeComboBox.selectedItem = "PLAN"
            form.areaComboBox.selectedItem = IssueDraftForm.NO_AREA
            form.summaryField.text = "스프린트 계획"
            form.descriptionArea.text = "스프린트 계획을 세운다."
            form.deliverablesArea.text = "계획 문서"

            assertEquals("[PLAN] 스프린트 계획", form.composedTitle())
            assertEquals(emptyList<String>(), dialog.currentView().errors)
        }
    }

    fun `test offers the work categories when the branch tells nothing`() {
        withDialog(context(featureId = null, storyKey = null, branch = "main")) { dialog ->
            assertEquals(WorkPolicy.DEFAULT.workCategories, (0 until dialog.form.scopeComboBox.itemCount).map {
                dialog.form.scopeComboBox.getItemAt(it)
            })
            assertEquals("PLAN", dialog.form.scopeComboBox.selectedItem)
        }
    }

    fun `test explains quietly why the story could not be read`() {
        withDialog(context(featureId = null, lookupNote = IssueDraftContextReader.NO_STORY_BRANCH)) { dialog ->
            dialog.form.selectType("task")

            assertTrue(dialog.currentView().errors.isNotEmpty())
            // 안내는 폼 안 주석으로만 보여주고 검증 오류로 다루지 않는다.
            assertTrue(dialog.currentView().errors.none { it.contains("브랜치") })
        }
    }

    fun `test remembers the reusable values when the draft is accepted`() {
        var remembered: RememberedInput? = null
        withDialog(onRemember = { remembered = it }) { dialog ->
            val form = dialog.form
            form.selectType("task")
            form.areaComboBox.selectedItem = "BE"
            form.summaryField.text = "회원가입 API"
            form.descriptionArea.text = "회원가입 API를 만든다."
            form.deliverablesArea.text = "POST /users"
            form.assigneeField.text = "다른 사람"

            dialog.doOKAction()
        }

        assertEquals("BE", remembered?.area)
        assertEquals("다른 사람", remembered?.assignee)
        assertEquals("AUTH-001", remembered?.scope)
        assertEquals(3, remembered?.storyPoints)
    }

    fun `test keeps a plain title field for stories and epics`() {
        withDialog { dialog ->
            val form = dialog.form

            form.selectType("story")
            form.titleField.text = "[AUTH-001] 이메일 회원가입"
            assertEquals("[AUTH-001] 이메일 회원가입", form.composedTitle())
            assertEquals("현재 브랜치를 스토리 브랜치 값으로 채운다", "feat/100-signup", form.branchField.text)
            assertEquals(setOf("type", "epicKey", "title", "branch", "sprint"), form.values().toRawInput().keys)

            form.selectType("epic")
            form.titleField.text = "[AUTH] 인증"
            assertEquals(setOf("type", "title"), form.values().toRawInput().keys)
        }
    }

    fun `test shows the draft json only after the preview button`() {
        withDialog { dialog ->
            val form = dialog.form
            form.selectType("task")
            form.areaComboBox.selectedItem = "FE"
            form.summaryField.text = "회원가입 폼"
            form.descriptionArea.text = "이메일 회원가입 폼을 만든다."
            form.deliverablesArea.text = "회원가입 화면"

            assertEquals("", dialog.previewText())
            dialog.showPreview()

            assertTrue(dialog.previewText().contains("\"issueType\": \"task\""))
            assertTrue(dialog.previewText().contains("\"summary\": \"[AUTH-001 | FE] 회원가입 폼\""))
        }
    }

    fun `test explains that a preview needs a valid input`() {
        withDialog { dialog ->
            dialog.showPreview()

            assertNull(dialog.currentView().json)
            assertEquals(CreateIssueDraftDialog.NO_DRAFT, dialog.previewText())
            assertTrue(dialog.problemsText().contains("오류 - "))
        }
    }

    fun `test shows the writing example of the selected type`() {
        withDialog { dialog ->
            dialog.form.selectType("story")
            assertTrue(dialog.form.templateArea.text.contains("feat/100-signup"))

            dialog.form.selectType("task")
            assertTrue(dialog.form.templateArea.text.contains("## 결과물"))
        }
    }
}
