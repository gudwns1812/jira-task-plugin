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
        assertNull("statuses는 더 이상 읽지 않는다", config.toRawMap()["jira"]?.let { (it as Map<*, *>)["statuses"] })
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
    fun `reads the ai section and round-trips it`() {
        val original = JiraTaskConfig.resolve(
            mapOf(
                "projectKey" to "S15P11A108",
                "ai" to mapOf("enabled" to true, "model" to "sonnet", "executablePath" to "C:/npm/claude.cmd"),
            ),
        )

        assertEquals(true, original.ai?.enabled)
        assertEquals("sonnet", original.ai?.model)
        assertEquals("C:/npm/claude.cmd", original.ai?.executablePath)
        assertEquals(original, JiraTaskConfig.resolve(original.toRawMap()))
    }

    @Test
    fun `sends the commit diff unless the setting says not to`() {
        // 예전 설정 파일에는 이 키가 없다. 없다고 기능이 반쪽이 되면 안 되므로 기본값은 켜짐이다.
        val old = JiraTaskConfig.resolve(mapOf("ai" to mapOf("enabled" to true)))
        assertEquals(true, old.ai?.sendDiff)

        val off = JiraTaskConfig.resolve(mapOf("ai" to mapOf("enabled" to true, "sendDiff" to false)))
        assertEquals(false, off.ai?.sendDiff)
        assertEquals("끈 것은 저장되어 살아남아야 한다", off, JiraTaskConfig.resolve(off.toRawMap()))
    }

    @Test
    fun `rejects a sendDiff value that is not true or false`() {
        assertThrows(PolicyConfigurationError::class.java) {
            JiraTaskConfig.resolve(mapOf("ai" to mapOf("sendDiff" to "가끔")))
        }
    }

    @Test
    fun `leaves the ai section out entirely when it was never configured`() {
        val config = JiraTaskConfig.resolve(mapOf("projectKey" to "S15P11A108"))

        assertNull(config.ai)
        assertEquals(setOf("projectKey"), config.toRawMap().keys)
    }

    @Test
    fun `rejects an ai section it does not understand`() {
        assertThrows(PolicyConfigurationError::class.java) {
            JiraTaskConfig.resolve(mapOf("ai" to mapOf("enabld" to true)))
        }
        // "maybe"를 조용히 false로 삼키지 않는다 — 잘못 적은 설정이 꺼 둔 것처럼 보이면 안 된다.
        assertThrows(PolicyConfigurationError::class.java) {
            JiraTaskConfig.resolve(mapOf("ai" to mapOf("enabled" to "maybe")))
        }
        assertThrows(PolicyConfigurationError::class.java) {
            JiraTaskConfig.resolve(mapOf("ai" to mapOf("model" to "  ")))
        }
        assertThrows(PolicyConfigurationError::class.java) {
            JiraTaskConfig.resolve(mapOf("ai" to "켜기"))
        }
    }

    @Test
    fun `mergeWith keeps the ai values this update did not mention`() {
        // ai는 키가 셋이라 통째로 덮이면 model이 사라진다. 한 겹 깊이로 합쳐야 한다.
        val current = JiraTaskConfig.resolve(
            mapOf("ai" to mapOf("enabled" to true, "model" to "sonnet")),
        )

        val merged = current.mergeWith(mapOf("ai" to mapOf("enabled" to false)))

        assertEquals(false, merged.ai?.enabled)
        assertEquals("sonnet", merged.ai?.model)
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
        assertEquals(JiraNamedValue("10001", "스토리"), afterIssueTypes.jira?.issueTypes?.story)
    }
}
