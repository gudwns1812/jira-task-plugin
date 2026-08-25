package io.github.gudwns1812.jiratask.infrastructure

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class JsonSupportTest {

    @Test
    fun `converts a nested map with lists to json and back without losing elements`() {
        val original: Map<String, Any?> = mapOf(
            "areas" to listOf("FE", "BE"),
            "storyPoints" to listOf(1, 2, 3),
            "labels" to mapOf("areas" to mapOf("INFRA" to "Infra")),
            "flag" to true,
            "missing" to null,
        )

        val json = original.toJsonElement()
        val roundTripped = json.toKotlinValue()

        assertEquals(listOf("FE", "BE"), (roundTripped as Map<*, *>)["areas"])
        assertEquals(listOf(1L, 2L, 3L), roundTripped["storyPoints"])
        assertEquals(mapOf("areas" to mapOf("INFRA" to "Infra")), roundTripped["labels"])
        assertEquals(true, roundTripped["flag"])
        assertEquals(null, roundTripped["missing"])
    }

    @Test
    fun `parses a json string into an equivalent kotlin structure`() {
        val parsed = JsonParser.parseString("""{"a":[1,2],"b":"x"}""").toKotlinValue()

        assertEquals(mapOf("a" to listOf(1L, 2L), "b" to "x"), parsed)
    }
}
