package io.github.gudwns1812.jiratask.ui.issue

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.gudwns1812.jiratask.domain.policy.WorkPolicy

/** 다이얼로그와 폼의 실제 위젯 동작(유형 전환, 실시간 검증, 미리보기)을 확인한다. */
class CreateIssueDraftDialogTest : BasePlatformTestCase() {

    private fun withDialog(block: (CreateIssueDraftDialog) -> Unit) {
        val dialog = CreateIssueDraftDialog(project, "S15P11A108", WorkPolicy.DEFAULT)
        try {
            block(dialog)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    private fun fillValidTask(form: IssueDraftForm) {
        form.selectType("task")
        form.epicKeyField.text = "S15P11A108-1"
        form.titleField.text = "[AUTH-001 | FE] 회원가입 폼"
        form.assigneeField.text = "박형준"
        form.priorityField.text = "High"
        form.storyPointsField.text = "3"
        form.sprintField.text = "Sprint 1"
        form.dueDateField.text = "2026-08-31"
        form.summaryArea.text = "이메일 회원가입 폼을 만든다."
        form.deliverablesArea.text = "회원가입 화면\n유효성 검사 로직"
    }

    fun `test starts disabled and reports the missing fields`() {
        withDialog { dialog ->
            assertFalse("빈 폼으로는 초안을 만들 수 없다", dialog.isOKActionEnabled)
            assertTrue(dialog.currentView().errors.isNotEmpty())
        }
    }

    fun `test validates while the user types`() {
        withDialog { dialog ->
            fillValidTask(dialog.form)

            assertEquals(emptyList<String>(), dialog.currentView().errors)
            assertTrue(dialog.isOKActionEnabled)
        }
    }

    fun `test shows the draft json only after the preview button`() {
        withDialog { dialog ->
            fillValidTask(dialog.form)

            assertEquals("미리보기를 누르기 전에는 초안을 보여주지 않는다", "", dialog.previewText())
            dialog.showPreview()

            assertTrue(dialog.previewText().contains("\"issueType\": \"task\""))
            assertTrue(dialog.problemsText().contains("오류 없음"))
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

    fun `test clears a stale preview when the input changes`() {
        withDialog { dialog ->
            fillValidTask(dialog.form)
            dialog.showPreview()
            assertTrue(dialog.previewText().isNotEmpty())

            dialog.form.titleField.text = "형식이 틀린 제목"

            assertEquals("", dialog.previewText())
        }
    }

    fun `test collects only the fields of the selected type`() {
        withDialog { dialog ->
            fillValidTask(dialog.form)
            dialog.form.selectType("epic")
            dialog.form.titleField.text = "[AUTH] 인증"

            val values = dialog.form.values()

            assertEquals("epic", values.type)
            assertEquals(setOf("type", "title"), values.toRawInput().keys)
            assertTrue(dialog.isOKActionEnabled)
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

    fun `test disables the ok action again when the input becomes invalid`() {
        withDialog { dialog ->
            fillValidTask(dialog.form)
            assertTrue(dialog.isOKActionEnabled)

            dialog.form.dueDateField.text = "2026-13-01"

            assertFalse(dialog.isOKActionEnabled)
            assertTrue(dialog.currentView().errors.any { it.startsWith("dueDate:") })
        }
    }
}
