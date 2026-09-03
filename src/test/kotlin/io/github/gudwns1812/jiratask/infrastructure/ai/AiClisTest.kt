package io.github.gudwns1812.jiratask.infrastructure.ai

import io.github.gudwns1812.jiratask.domain.policy.AiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 설정에 저장된 값과 실제로 부를 CLI를 잇는다. */
class AiClisTest {

    @Test
    fun `maps every provider the settings can hold`() {
        // 설정에 고를 수 있는데 부를 수 없는 provider가 있으면 사용자는 죽은 선택지를 만난다.
        AiProvider.entries.forEach { provider ->
            assertEquals(provider.id, AiClis.of(provider).id)
        }
        assertEquals(AiProvider.entries.size, AiClis.all.size)
    }

    @Test
    fun `falls back to claude for settings written before there was a choice`() {
        assertEquals(ClaudeCodeCli, AiClis.of(null))
        assertEquals(ClaudeCodeCli, AiClis.byId(null))
        assertEquals(ClaudeCodeCli, AiClis.byId("사라진-provider"))
    }

    @Test
    fun `every cli says how to install it and what to call it`() {
        AiClis.all.forEach { cli ->
            assertTrue(cli.displayName.isNotBlank())
            assertTrue(cli.installHint.isNotBlank())
            assertTrue(cli.notFound().contains(cli.displayName))
            assertTrue(cli.binaryNames(OperatingSystem.MAC).isNotEmpty())
        }
    }
}
