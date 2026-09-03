package io.github.gudwns1812.jiratask.infrastructure.ai

/**
 * 지금 돌고 있는 운영체제.
 *
 * `System.getProperty("os.name")`을 쓰는 자리마다 직접 읽지 않고 **값으로 받아 넘긴다.**
 * 그러지 않으면 Windows 개발기에서 mac 분기를, mac에서 Windows 분기를 시험해 볼 방법이 없다.
 * 실행 파일 이름과 설치 자리가 OS마다 다른 기능이라 그 확인이 특히 중요하다.
 */
enum class OperatingSystem {
    WINDOWS,
    MAC,
    LINUX,
    ;

    companion object {

        fun current(): OperatingSystem = of(System.getProperty("os.name").orEmpty())

        fun of(osName: String): OperatingSystem = when {
            osName.startsWith("Windows", ignoreCase = true) -> WINDOWS
            osName.startsWith("Mac", ignoreCase = true) -> MAC
            else -> LINUX
        }
    }
}
