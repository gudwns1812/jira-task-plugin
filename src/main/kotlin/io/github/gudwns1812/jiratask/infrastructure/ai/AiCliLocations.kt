package io.github.gudwns1812.jiratask.infrastructure.ai

import java.util.concurrent.ConcurrentHashMap

/**
 * 찾아 둔 실행 파일 자리를 세션 동안 기억한다.
 *
 * 탐지는 후보마다 `--version`을 부르는 일이라 공짜가 아니다. 기억해 두지 않으면 **초안을 만들 때마다**
 * 그 값을 치르고, 후보가 많은 macOS에서 그대로 지연이 된다.
 *
 * 사용자가 CLI를 새로 깔았을 수도 있으므로 [forget]으로 지울 수 있게 둔다 —
 * 설정 화면의 `다시 탐지`가 그것이다.
 */
object AiCliLocations {

    private val found = ConcurrentHashMap<String, String>()

    /** 못 찾았다는 사실도 기억한다. 없는 CLI를 매번 다시 뒤지는 것이 가장 비싸다. */
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
