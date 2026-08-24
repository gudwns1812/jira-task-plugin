package io.github.gudwns1812.jiratask.ui.issue

import com.google.gson.JsonParser
import io.github.gudwns1812.jiratask.domain.policy.WorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 폼 입력 → 검증 결과 + 요청 초안 JSON. CLI의 `validate`/`create --dry-run` 출력과 같은 내용을 확인한다. */
class IssueDraftPresenterTest {

    private val projectKey = "S15P11A108"

    private fun validTask() = IssueFormInput(
        type = "task",
        epicKey = "S15P11A108-1",
        storyKey = "S15P11A108-100",
        title = "[AUTH-001 | FE] 회원가입 폼",
        assignee = "박형준",
        priority = "High",
        storyPoints = "3",
        sprint = "Sprint 1",
        dueDate = "2026-08-31",
        summary = "이메일 회원가입 폼을 만든다.",
        deliverables = "회원가입 화면\n유효성 검사 로직\n",
    )

    private fun present(input: IssueFormInput, policy: WorkPolicy = WorkPolicy.DEFAULT) =
        IssueDraftPresenter.present(input, projectKey, policy)

    @Test
    fun `builds a draft for a valid task with derived labels`() {
        val view = present(validTask())

        assertEquals(emptyList<String>(), view.errors)
        assertTrue(view.isValid)
        assertEquals(listOf("FE"), view.labels)
        val json = view.json!!
        assertTrue(json.contains("\"projectKey\": \"S15P11A108\""))
        assertTrue(json.contains("\"issueType\": \"task\""))
        assertTrue(json.contains("\"summary\": \"[AUTH-001 | FE] 회원가입 폼\""))
        assertTrue(json.contains("\"storyPoints\": 3"))
        assertTrue(json.contains("\"epicKey\": \"S15P11A108-1\""))
        assertTrue(json.contains("## 설명"))
        assertTrue(json.contains("## 결과물"))
    }

    @Test
    fun `splits deliverables by line and ignores blank lines`() {
        val view = present(validTask().copy(deliverables = "\n첫 번째 결과물\n\n   \n두 번째 결과물\n"))

        assertTrue(view.isValid)
        assertTrue(view.json!!.contains("[] 첫 번째 결과물\\n[] 두 번째 결과물"))
    }

    @Test
    fun `builds a draft for a valid story with the branch as description`() {
        val view = present(
            IssueFormInput(
                type = "story",
                epicKey = "S15P11A108-1",
                title = "[AUTH-001] 이메일 회원가입",
                branch = "feat/100-signup",
            ),
        )

        assertTrue(view.isValid)
        assertEquals(listOf("feat"), view.labels)
        assertTrue(view.json!!.contains("feat/100-signup"))
        assertFalse("빈 스프린트는 초안에 담지 않는다", view.json!!.contains("sprint"))
    }

    @Test
    fun `builds a draft for a valid epic without a description`() {
        val view = present(IssueFormInput(type = "epic", title = "[AUTH] 인증"))

        assertTrue(view.isValid)
        assertTrue(view.json!!.contains("\"issueType\": \"epic\""))
        assertFalse(view.json!!.contains("description"))
    }

    @Test
    fun `reports every missing required field at once`() {
        val view = present(IssueFormInput(type = "task"))

        assertNull(view.json)
        assertFalse(view.isValid)
        assertTrue(view.errors.any { it.startsWith("epicKey:") })
        assertTrue(view.errors.any { it.startsWith("title:") })
        assertTrue(view.errors.any { it.startsWith("assignee:") })
        assertTrue(view.errors.any { it.startsWith("priority:") })
        assertTrue(view.errors.any { it.startsWith("sprint:") })
        assertTrue(view.errors.any { it.startsWith("dueDate:") })
        assertTrue(view.errors.any { it.startsWith("storyPoints:") })
        assertTrue(view.errors.any { it.startsWith("description.summary:") })
        assertTrue(view.errors.any { it.startsWith("description.deliverables:") })
    }

    @Test
    fun `rejects a story point that is not in the policy`() {
        val view = present(validTask().copy(storyPoints = "4"))

        assertTrue(view.errors.any { it.contains("스토리 포인트는 1, 2, 3, 5, 8 중 하나여야 합니다.") })
        assertNull(view.json)
    }

    @Test
    fun `rejects a story point that is not a number`() {
        val view = present(validTask().copy(storyPoints = "삼"))

        assertTrue(view.errors.any { it.startsWith("storyPoints:") })
        assertNull(view.json)
    }

    @Test
    fun `rejects an impossible due date`() {
        val view = present(validTask().copy(dueDate = "2026-02-30"))

        assertTrue(view.errors.any { it.contains("YYYY-MM-DD") })
        assertNull(view.json)
    }

    @Test
    fun `rejects a title that does not follow the rule`() {
        val view = present(validTask().copy(title = "회원가입 폼"))

        assertTrue(view.errors.any { it.startsWith("title:") })
        assertNull(view.json)
    }

    @Test
    fun `rejects an unknown area in the title`() {
        val view = present(validTask().copy(title = "[AUTH-001 | XX] 회원가입 폼"))

        assertTrue(view.errors.any { it.contains("담당 영역은") })
    }

    @Test
    fun `keeps building the draft when only warnings are reported`() {
        val view = present(validTask().copy(deliverables = "폼"))

        assertEquals(emptyList<String>(), view.errors)
        assertTrue(view.warnings.any { it.startsWith("description.deliverables:") })
        assertTrue("경고만 있으면 초안은 만들어진다", view.isValid)
    }

    @Test
    fun `honours a custom policy`() {
        val policy = WorkPolicy.DEFAULT.copy(areas = listOf("FE"), storyPoints = listOf(1, 2))

        val rejected = present(validTask().copy(storyPoints = "3"), policy)
        val accepted = present(validTask().copy(storyPoints = "2"), policy)

        assertTrue(rejected.errors.any { it.contains("1, 2 중 하나") })
        assertTrue(accepted.isValid)
    }

    @Test
    fun `omits optional keys that are blank so the parser does not see them`() {
        val raw = validTask().copy(storyKey = "   ").toRawInput()

        assertFalse(raw.containsKey("storyKey"))
        assertEquals("task", raw["type"])
        assertEquals(3, raw["storyPoints"])
    }

    @Test
    fun `sends only the keys of the selected type`() {
        val storyRaw = IssueFormInput(
            type = "story",
            title = "[AUTH-001] 회원가입",
            epicKey = "S15P11A108-1",
            branch = "feat/100-signup",
            assignee = "무시되는 값",
            dueDate = "2026-08-31",
        ).toRawInput()

        assertEquals(setOf("type", "epicKey", "title", "branch"), storyRaw.keys)
    }

    @Test
    fun `produces the same draft as the CLI dry run for the same input`() {
        // dist/cli/index.js create -f task.json -p S15P11A108 --dry-run 의 실제 출력
        val cliOutput = """
            {
              "projectKey": "S15P11A108",
              "issueType": "task",
              "summary": "[AUTH-001 | FE] 회원가입 폼",
              "description": "## 설명\n이메일 회원가입 폼을 만든다.\n\n## 결과물\n[] 회원가입 화면\n[] 유효성 검사 로직",
              "labels": ["FE"],
              "policyFields": { "storyPoints": 3, "sprint": "Sprint 1" },
              "relationships": { "epicKey": "S15P11A108-1" },
              "assignee": "박형준",
              "priority": "High",
              "dueDate": "2026-08-31"
            }
        """.trimIndent()

        val view = present(validTask().copy(storyKey = ""))

        assertEquals(JsonParser.parseString(cliOutput), JsonParser.parseString(view.json!!))
    }

    @Test
    fun `offers the CLI templates as writing examples`() {
        assertTrue(IssueDraftPresenter.templateFor("task").contains("## 결과물"))
        assertTrue(IssueDraftPresenter.templateFor("story").contains("feat/100-signup"))
        assertEquals("", IssueDraftPresenter.templateFor("epic"))
    }
}
