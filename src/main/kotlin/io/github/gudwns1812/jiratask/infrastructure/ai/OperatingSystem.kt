package io.github.gudwns1812.jiratask.infrastructure.ai

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
