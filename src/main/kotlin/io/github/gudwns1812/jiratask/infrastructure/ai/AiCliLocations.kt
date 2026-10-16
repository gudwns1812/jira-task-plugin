package io.github.gudwns1812.jiratask.infrastructure.ai

import java.util.concurrent.ConcurrentHashMap

object AiCliLocations {

    private val found = ConcurrentHashMap<String, String>()

    private val missing = ConcurrentHashMap.newKeySet<String>()

    fun locate(cli: AiCli, os: OperatingSystem, canRun: (String) -> Boolean): String? {
        val key = "${cli.id}|$os"
        found[key]?.let { return it }
        if (key in missing) {
            return null
        }

        val located = AiCliDiscovery(os = os, canRun = canRun).find(cli.binaryNames(os))
        if (located == null) {
            missing.add(key)
        } else {
            found[key] = located
        }
        return located
    }

    fun forget() {
        found.clear()
        missing.clear()
    }
}
