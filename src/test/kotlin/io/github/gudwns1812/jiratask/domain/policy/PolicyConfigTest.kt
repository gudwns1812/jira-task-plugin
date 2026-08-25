package io.github.gudwns1812.jiratask.domain.policy

import io.github.gudwns1812.jiratask.domain.jira.JiraNamedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyConfigTest {

    @Test
    fun `default policy config matches RULE md defaults`() {
        val policy = WorkPolicy.DEFAULT

        assertEquals(listOf("FE", "BE", "AI", "QA", "INFRA"), policy.areas)
        assertEquals(listOf(1, 2, 3, 5, 8), policy.storyPoints)
        assertEquals("Infra", policy.labels.areas["INFRA"])
    }

    @Test
    fun `overrides areas and area labels`() {
        val policy = WorkPolicy.resolve(
            mapOf(
                "areas" to listOf("FE", "MOBILE"),
                "labels" to mapOf("areas" to mapOf("MOBILE" to "Mobile")),
            ),
        )

        assertEquals(listOf("FE", "MOBILE"), policy.areas)
        assertEquals("Mobile", policy.labels.areas["MOBILE"])
    }

    @Test
    fun `rejects a story point outside the allowed set`() {
        assertThrows(PolicyConfigurationError::class.java) {
            WorkPolicy.resolve(mapOf("storyPoints" to listOf(1, 2, 13)))
        }
    }

    @Test
    fun `rejects a label key that is not in the allowed area list`() {
        assertThrows(PolicyConfigurationError::class.java) {
            WorkPolicy.resolve(mapOf("labels" to mapOf("areas" to mapOf("MOBILE" to "Mobile"))))
        }
    }

    @Test
    fun `rejects duplicate area values`() {
        assertThrows(PolicyConfigurationError::class.java) {
            WorkPolicy.resolve(mapOf("areas" to listOf("FE", "FE")))
        }
    }

    @Test
    fun `reads project key alongside policy overrides`() {
        val config = JiraTaskConfig.resolve(mapOf("projectKey" to "S15P11A108", "areas" to listOf("FE")))

        assertEquals("S15P11A108", config.projectKey)
        assertEquals(listOf("FE"), config.policy.areas)
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
    fun `reports an invalid project key together with a duplicate area in one error`() {
        val error = assertThrows(PolicyConfigurationError::class.java) {
            JiraTaskConfig.resolve(mapOf("projectKey" to "team project", "areas" to listOf("FE", "FE")))
        }

        assertTrue(error.message!!.contains("projectKey"))
        assertTrue(error.message!!.contains("areas"))
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
    fun `mergeWith preserves project key and policy when only jira base url changes`() {
        val current = JiraTaskConfig.resolve(mapOf("projectKey" to "S15P11A108"))

        val merged = current.mergeWith(mapOf("jira" to mapOf("baseUrl" to "http://127.0.0.1:8080")))

        assertEquals("S15P11A108", merged.projectKey)
        assertEquals("http://127.0.0.1:8080", merged.jira?.baseUrl)
        assertEquals(listOf(1, 2, 3, 5, 8), merged.policy.storyPoints)
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
