package io.github.gudwns1812.jiratask.infrastructure.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * OS를 값으로 다룬다.
 *
 * 이 테스트가 있는 이유는 개발기가 한 대뿐이기 때문이다. `System.getProperty`를 코드 안에서 바로
 * 읽으면 Windows에서는 mac 갈래를, mac에서는 Windows 갈래를 영영 확인할 수 없다.
 */
class OperatingSystemTest {

    @Test
    fun `reads the os out of what the jvm reports`() {
        assertEquals(OperatingSystem.WINDOWS, OperatingSystem.of("Windows 11"))
        assertEquals(OperatingSystem.MAC, OperatingSystem.of("Mac OS X"))
        assertEquals(OperatingSystem.LINUX, OperatingSystem.of("Linux"))
    }

    @Test
    fun `treats anything it does not know as linux`() {
        // 모르는 OS에서 Windows 갈래를 타면 `claude.cmd`를 찾다가 못 찾는다. 유닉스 쪽이 안전한 기본값이다.
        assertEquals(OperatingSystem.LINUX, OperatingSystem.of("FreeBSD"))
        assertEquals(OperatingSystem.LINUX, OperatingSystem.of(""))
    }

    @Test
    fun `does not care about letter case`() {
        assertEquals(OperatingSystem.WINDOWS, OperatingSystem.of("windows server 2022"))
        assertEquals(OperatingSystem.MAC, OperatingSystem.of("mac os x"))
    }
}
