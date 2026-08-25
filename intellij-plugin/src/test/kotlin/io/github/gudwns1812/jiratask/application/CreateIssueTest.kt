package io.github.gudwns1812.jiratask.application

import io.github.gudwns1812.jiratask.domain.jira.CreateRequestDraft
import io.github.gudwns1812.jiratask.domain.jira.JiraCreatedIssue
import io.github.gudwns1812.jiratask.domain.jira.JiraFieldIds
import io.github.gudwns1812.jiratask.domain.jira.JiraIssueCreator
import io.github.gudwns1812.jiratask.domain.jira.JiraNetworkError
import io.github.gudwns1812.jiratask.domain.jira.JiraRequestError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 검증된 초안을 실제 Jira 이슈로 만드는 규칙. */
class CreateIssueTest {

    private class FakeCreator(
        private val fieldIds: JiraFieldIds = JiraFieldIds(storyPoints = "customfield_10016", sprint = "customfield_10020"),
        private val accountIds: Map<String, String> = mapOf("박형준" to "account-1"),
        private val createFailure: Exception? = null,
        private val linkFailure: Exception? = null,
        private val fieldIdsFailure: Exception? = null,
        private val sprintIds: Map<String, Int> = emptyMap(),
        private val sprintLookupFailure: Exception? = null,
    ) : JiraIssueCreator {
        var createdFields: Map<String, Any?>? = null
        val links = mutableListOf<Triple<String, String, String>>()

        override fun createIssue(fields: Map<String, Any?>): JiraCreatedIssue {
            createdFields = fields
            createFailure?.let { throw it }
            return JiraCreatedIssue("S15P11A108-257")
        }

        override fun findAccountId(query: String): String? = accountIds[query]

        override fun findFieldIds(): JiraFieldIds {
            fieldIdsFailure?.let { throw it }
            return fieldIds
        }

        override fun findSprintId(projectKey: String, sprintName: String): Int? {
            sprintLookupFailure?.let { throw it }
            return sprintIds[sprintName]
        }

        override fun linkIssues(inwardKey: String, outwardKey: String, linkTypeName: String) {
            linkFailure?.let { throw it }
            links += Triple(inwardKey, outwardKey, linkTypeName)
        }
    }

    private val issueTypeIds = IssueTypeIds(epic = "10003", story = "10001", task = "10002")

    private fun taskDraft() = CreateRequestDraft(
        projectKey = "S15P11A108",
        issueType = "task",
        summary = "[AUTH-001 | FE] 회원가입 폼",
        description = "## 설명\n이메일 회원가입 폼을 만든다.\n\n## 결과물\n[] 회원가입 화면\n[] 유효성 검사",
        labels = listOf("FE"),
        assignee = "박형준",
        priority = "Low",
        dueDate = "2026-08-31",
        policyFields = CreateRequestDraft.PolicyFields(storyPoints = 3, sprint = "Sprint 7"),
        relationships = CreateRequestDraft.Relationships(epicKey = "S15P11A108-1", storyKey = "S15P11A108-100"),
    )

    private fun execute(creator: FakeCreator, sprintId: Int? = 42) =
        CreateIssueUseCase(creator).execute(
            CreateIssueCommand(taskDraft(), issueTypeIds, sprintId = sprintId),
        )

    @Test
    fun `creates the issue with every field this jira can take`() {
        val creator = FakeCreator()

        val result = execute(creator)

        assertEquals(CreateIssueResult.Created("S15P11A108-257"), result)
        val fields = creator.createdFields!!
        assertEquals(mapOf("key" to "S15P11A108"), fields["project"])
        assertEquals(mapOf("id" to "10002"), fields["issuetype"])
        assertEquals("[AUTH-001 | FE] 회원가입 폼", fields["summary"])
        assertEquals(listOf("FE"), fields["labels"])
        assertEquals(mapOf("name" to "Low"), fields["priority"])
        assertEquals("2026-08-31", fields["duedate"])
        assertEquals(mapOf("key" to "S15P11A108-1"), fields["parent"])
        assertEquals(mapOf("accountId" to "account-1"), fields["assignee"])
        assertEquals(3, fields["customfield_10016"])
        assertEquals(42, fields["customfield_10020"])
    }

    @Test
    fun `writes the description as an atlassian document`() {
        val creator = FakeCreator()

        execute(creator)

        @Suppress("UNCHECKED_CAST")
        val description = creator.createdFields!!["description"] as Map<String, Any?>
        assertEquals("doc", description["type"])
        @Suppress("UNCHECKED_CAST")
        val content = description["content"] as List<Map<String, Any?>>
        assertEquals(listOf("heading", "paragraph", "heading", "taskList"), content.map { it["type"] })
    }

    @Test
    fun `links the story to the created task`() {
        val creator = FakeCreator()

        execute(creator)

        assertEquals(listOf(Triple("S15P11A108-100", "S15P11A108-257", "Relates")), creator.links)
    }

    @Test
    fun `reports a link failure without failing the creation`() {
        val creator = FakeCreator(linkFailure = JiraRequestError("링크 실패"))

        val result = execute(creator)

        assertEquals(CreateIssueResult.Created("S15P11A108-257", emptyList(), linkFailed = true), result)
    }

    @Test
    fun `says which fields it could not set`() {
        val creator = FakeCreator(fieldIds = JiraFieldIds(), accountIds = emptyMap())

        val result = execute(creator, sprintId = null)

        val created = result as CreateIssueResult.Created
        assertEquals(
            listOf("담당자(박형준)", "스토리 포인트(3)", "스프린트(Sprint 7: 이 Jira에서 스프린트 필드를 찾지 못했습니다)"),
            created.unsetFields,
        )
        assertFalse(creator.createdFields!!.containsKey("assignee"))
    }

    @Test
    fun `does not set the sprint without its id`() {
        val creator = FakeCreator()

        val result = execute(creator, sprintId = null)

        assertEquals(
            listOf("스프린트(Sprint 7: 프로젝트 보드의 진행 중·예정 스프린트에서 같은 이름을 찾지 못했습니다)"),
            (result as CreateIssueResult.Created).unsetFields,
        )
        assertFalse(creator.createdFields!!.containsKey("customfield_10020"))
    }

    @Test
    fun `resolves the sprint name when the story did not provide its id`() {
        val creator = FakeCreator(sprintIds = mapOf("Sprint 7" to 314))

        val result = execute(creator, sprintId = null)

        assertEquals(CreateIssueResult.Created("S15P11A108-257"), result)
        assertEquals(314, creator.createdFields!!["customfield_10020"])
    }

    @Test
    fun `leaves the sprint unset when its name lookup fails`() {
        val creator = FakeCreator(sprintLookupFailure = JiraNetworkError())

        val result = execute(creator, sprintId = null)

        assertTrue(
            (result as CreateIssueResult.Created).unsetFields.single().contains("같은 이름을 찾지 못했습니다"),
        )
        assertFalse(creator.createdFields!!.containsKey("customfield_10020"))
    }

    @Test
    fun `keeps going when the field lookup itself fails`() {
        val creator = FakeCreator(fieldIdsFailure = JiraNetworkError())

        val result = execute(creator)

        assertTrue(result is CreateIssueResult.Created)
        assertEquals(
            listOf("스토리 포인트(3)", "스프린트(Sprint 7: 이 Jira에서 스프린트 필드를 찾지 못했습니다)"),
            (result as CreateIssueResult.Created).unsetFields,
        )
    }

    @Test
    fun `refuses when the issue type id is unknown`() {
        val creator = FakeCreator()

        val result = CreateIssueUseCase(creator).execute(
            CreateIssueCommand(taskDraft(), IssueTypeIds(null, null, null)),
        )

        assertTrue(result is CreateIssueResult.Failure)
        assertTrue((result as CreateIssueResult.Failure).message.contains("설정 마법사"))
    }

    @Test
    fun `passes the jira message through when creation is rejected`() {
        val creator = FakeCreator(createFailure = JiraRequestError("Jira 요청이 워크플로 규칙을 만족하지 않습니다."))

        val result = execute(creator)

        assertEquals(
            CreateIssueResult.Failure("Jira 요청이 워크플로 규칙을 만족하지 않습니다."),
            result,
        )
    }

    @Test
    fun `hides unexpected failures behind a plain message`() {
        val creator = FakeCreator(createFailure = IllegalStateException("internal: token=abc"))

        val result = execute(creator)

        assertEquals(CreateIssueResult.Failure("Jira 이슈를 만들지 못했습니다."), result)
    }

    @Test
    fun `creates an epic without a parent or a link`() {
        val creator = FakeCreator()
        val epic = CreateRequestDraft(
            projectKey = "S15P11A108",
            issueType = "epic",
            summary = "[AUTH] 인증",
            description = null,
            labels = emptyList(),
            policyFields = CreateRequestDraft.PolicyFields(),
            relationships = CreateRequestDraft.Relationships(),
        )

        val result = CreateIssueUseCase(creator).execute(CreateIssueCommand(epic, issueTypeIds))

        assertEquals(CreateIssueResult.Created("S15P11A108-257"), result)
        assertEquals(mapOf("id" to "10003"), creator.createdFields!!["issuetype"])
        assertFalse(creator.createdFields!!.containsKey("parent"))
        assertFalse(creator.createdFields!!.containsKey("description"))
        assertTrue(creator.links.isEmpty())
    }
}
