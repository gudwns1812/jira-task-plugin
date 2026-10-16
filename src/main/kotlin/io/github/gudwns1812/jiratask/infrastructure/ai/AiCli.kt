package io.github.gudwns1812.jiratask.infrastructure.ai

import java.io.File

data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String) {
    fun complaint(displayName: String): String {
        val unknown = "${displayName}이(가) 이유를 밝히지 않고 실패했습니다(종료 코드 $exitCode)."
        val said = listOf(stderr, stdout).map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return unknown

        return said.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(MAX_COMPLAINT) ?: unknown
    }

    private companion object {
        const val MAX_COMPLAINT = 300
    }
}

class Invocation(
    val command: List<String>,
    val readText: (ProcessResult) -> String?,
    val cleanUp: () -> Unit = {},
    val errorIn: (ProcessResult) -> String? = { null },
)

interface AiCli {
    val id: String

    val displayName: String

    val models: List<String>

    fun configuredModel(userHome: File): String? = null

    val installHint: String

    fun binaryNames(os: OperatingSystem): List<String>

    fun isSessionVariable(name: String): Boolean = false

    fun invocationOf(executable: String, model: String?): Invocation

    fun notFound(): String =
        "$displayName CLI를 찾을 수 없습니다. $installHint " +
            "이미 설치했다면 터미널의 `${binaryNames(OperatingSystem.current()).first()} --version`이 도는 경로를 " +
            "설정의 실행 파일 경로에 넣으세요."
}

sealed interface AiAnswer {
    data class Said(val text: String) : AiAnswer

    data class NotAvailable(val message: String) : AiAnswer

    data class Failed(val message: String) : AiAnswer
}
