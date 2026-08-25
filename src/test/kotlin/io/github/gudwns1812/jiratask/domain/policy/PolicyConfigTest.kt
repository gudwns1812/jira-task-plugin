package io.github.gudwns1812.jiratask.domain.policy

import io.github.gudwns1812.jiratask.domain.jira.JiraNamedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyConfigTest {

    @Test
    fun `ignores the work policy keys an older version saved`() {
        // 이슈를 만들 때만 쓰던 값이다. 남아 있어도 오류가 아니라 무시 대상이다.
        val config = JiraTaskConfig.resolve(
            mapOf(
                "projectKey" to "S15P11A108",
                "areas" to listOf("FE", "MOBILE"),
                "workCategories" to listOf("PLAN"),
                "storyPoints" to listOf(1, 2, 13),
                "labels" to mapOf("areas" to mapOf("MOBILE" to "Mobile")),
            ),
        )

        assertEquals("S15P11A108", config.projectKey)
    }

    @Test
    fun `reads a file that has nothing but the old policy keys`() {
        val config = JiraTaskConfig.resolve(mapOf("areas" to listOf("FE")))

        assertNull(config.projectKey)
        assertNull(config.jira)
    }

    @Test
    fun `does not write the old policy keys back out`() {
        val config = JiraTaskConfig.resolve(mapOf("projectKey" to "S15P11A108", "areas" to listOf("FE")))

        assertEquals(setOf("projectKey"), config.toRawMap().keys)
    }

    @Test
    fun `still rejects a key it has never known`() {
        assertThrows(PolicyConfigurationError::class.java) {
            JiraTaskConfig.resolve(mapOf("projectKey" to "S15P11A108", "unknownField" to 1))
        }
    }

    @Test
    fun `keeps jira ids and display names saved by the setup wizard`() {
        val config = JiraTaskConfig.resolve(
            mapOf(
                "projectKey" to "S15P11A108",
                "jira" to mapOf(
                    "baseUrl" to "https://team.atlassian.net",
                    "project" to mapOf("id" to "10001", "name" to "특화 프로젝트"),
                    "issueTypes" to mapOf(
                        "story" to mapOf("id" to "10", "name" to "스토리"),
                        "task" to mapOf("id" to "11", "name" to "작업"),
                        "epic" to mapOf("id" to "12", "name" to "에픽"),
                    ),
                    "statuses" to mapOf(
                        "story" to mapOf("start" to mapOf("id" to "3", "name" to "진행 중")),
                        "task" to mapOf("done" to mapOf("id" to "4", "name" to "완료")),
                    ),
                ),
            ),
        )

        assertEquals(JiraNamedValue("10", "스토리"), config.jira?.issueTypes?.story)
        assertEquals(JiraNamedValue("3", "진행 중"), config.jira?.statuses?.story?.start)
    }

    @Test
    fun `rejects an empty project key`() {
        assertThrows(PolicyConfigurationError::class.java) {
            JiraTaskConfig.resolve(mapOf("projectKey" to ""))
        }
    }

    @Test
    fun `rejects a project key with invalid characters`() {
        assertThrows(PolicyConfigurationError::class.java) {
            JiraTaskConfig.resolve(mapOf("projectKey" to "team project"))
        }
    }

    @Test
    fun `reports the invalid project key and the bad base url in one error`() {
        val error = assertThrows(PolicyConfigurationError::class.java) {
            JiraTaskConfig.resolve(
                mapOf("projectKey" to "team project", "jira" to mapOf("baseUrl" to "ftp://team.atlassian.net")),
            )
        }

        assertTrue(error.message!!.contains("projectKey"))
        assertTrue(error.message!!.contains("baseUrl"))
    }

    @Test
    fun `rejects a non-http jira base url`() {
        assertThrows(PolicyConfigurationError::class.java) {
            JiraTaskConfig.resolve(mapOf("jira" to mapOf("baseUrl" to "ftp://team.atlassian.net")))
        }
    }

    @Test
    fun `rejects jira settings with no recognized values`() {
        assertThrows(PolicyConfigurationError::class.java) {
            JiraTaskConfig.resolve(mapOf("jira" to emptyMap<String, Any?>()))
        }
    }

    @Test
    fun `round-trips through toRawMap and JiraTaskConfig resolve`() {
        val original = JiraTaskConfig.resolve(
            mapOf(
                "projectKey" to "S15P11A108",
                "jira" to mapOf(
                    "baseUrl" to "https://team.atlassian.net",
                    "issueTypes" to mapOf("story" to mapOf("id" to "10", "name" to "스토리")),
                ),
            ),
        )

        val roundTripped = JiraTaskConfig.resolve(original.toRawMap())

        assertEquals(original, roundTripped)
    }

    @Test
    fun `mergeWith preserves the project key when only the jira base url changes`() {
        val current = JiraTaskConfig.resolve(mapOf("projectKey" to "S15P11A108"))

        val merged = current.mergeWith(mapOf("jira" to mapOf("baseUrl" to "http://127.0.0.1:8080")))

        assertEquals("S15P11A108", merged.projectKey)
        assertEquals("http://127.0.0.1:8080", merged.jira?.baseUrl)
    }

    @Test
    fun `mergeWith keeps previously saved jira fields when saving new ones`() {
        val afterBaseUrl = JiraTaskConfig.resolve(mapOf("projectKey" to "S15P11A108"))
            .mergeWith(mapOf("jira" to mapOf("baseUrl" to "http://127.0.0.1:8080")))

        val afterIssueTypes = afterBaseUrl.mergeWith(
            mapOf(
                "jira" to mapOf(
                    "issueTypes" to mapOf("story" to mapOf("id" to "10001", "name" to "스토리")),
                    "statuses" to mapOf("story" to mapOf("start" to mapOf("id" to "21", "name" to "진행 중"))),
                ),
            ),
        )

        assertEquals("http://127.0.0.1:8080", afterIssueTypes.jira?.baseUrl)
        assertEquals(JiraNamedValue("10001", "스토리"), afterIssueTypes.jira?.issueTypes?.story)
        assertEquals(JiraNamedValue("21", "진행 중"), afterIssueTypes.jira?.statuses?.story?.start)
    }
}
