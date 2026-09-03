package io.github.gudwns1812.jiratask.infrastructure.ai

import java.io.File

/** 프로세스를 부르고 받은 것. stderr를 버리지 않는 이유는 [AiCliRunner]에 적어 두었다. */
data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String) {

    /** CLI가 한 말을 그대로 올린다. 우리가 지어낸 문구보다 저쪽 설명이 늘 낫다. */
    fun complaint(displayName: String): String {
        val unknown = "${displayName}이(가) 이유를 밝히지 않고 실패했습니다(종료 코드 $exitCode)."
        val said = listOf(stderr, stdout).map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return unknown

        return said.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(MAX_COMPLAINT) ?: unknown
    }

    private companion object {
        const val MAX_COMPLAINT = 300
    }
}

/**
 * 한 번의 호출. 무엇을 부르고 답을 어디서 꺼내는지를 담는다.
 *
 * [readText]를 함수로 두는 이유는 CLI마다 답을 두는 자리가 다르기 때문이다 — Claude Code는 stdout에
 * JSON 봉투를 찍고, Codex는 `--output-last-message`가 가리킨 파일에 최종 답만 쓴다. 임시 파일을
 * 쓰는 쪽이 있어 [cleanUp] 자리를 함께 남긴다.
 */
class Invocation(
    val command: List<String>,
    val readText: (ProcessResult) -> String?,
    val cleanUp: () -> Unit = {},
    /**
     * 이 결과가 실패라면 **사용자가 무엇을 하면 되는지** 담은 문구. 실패가 아니면 null.
     *
     * 두 가지가 이 자리로 온다. 하나는 종료 코드 0으로 끝나는 실패(로그인 만료가 그렇다 —
     * 프로세스는 멀쩡히 끝나고 봉투 안에만 이유가 적힌다). 다른 하나는 CLI가 뱉은 말이 그대로는
     * 쓸모없는 경우(`unknown option`이 그렇다 — 그 말만으로는 버전을 올리라는 뜻인 줄 알 수 없다).
     */
    val errorIn: (ProcessResult) -> String? = { null },
)

/**
 * 부를 수 있는 AI CLI 하나.
 *
 * 프로세스를 다루는 어려운 부분(스트림 교착, 시한, 중첩 세션)은 [AiCliRunner]가 모두 안다.
 * 여기 남는 것은 **그 CLI만의 것** — 실행 파일 이름, 명령줄, 답이 들어 있는 자리뿐이다.
 * 새 provider를 붙이는 일이 이 인터페이스 하나를 구현하는 일이 되도록 그렇게 갈랐다.
 */
interface AiCli {

    /** 설정 파일에 저장되는 값. 사람이 읽을 이름이 바뀌어도 이건 바뀌지 않는다. */
    val id: String

    /** 화면과 오류 문구에 쓰는 이름. */
    val displayName: String

    /**
     * 설정 화면 모델 콤보에 띄울 **예시**. 여기 없는 이름도 직접 칠 수 있다.
     *
     * 카탈로그가 아니다 — 어떤 모델이 있는지 알려주는 API도, 목록을 내주는 CLI 명령도 없다.
     * 지어낸 목록은 금세 낡아, 사용자가 고르기만 했는데 실행에서 거부당하게 만든다.
     */
    val models: List<String>

    /**
     * 이 CLI가 **자기 설정에 적어 둔** 모델. 없거나 못 읽으면 null.
     *
     * 우리가 아는 유일한 진짜 모델 이름이다. 예시 목록보다 이쪽을 먼저 보여 준다 —
     * 사용자가 이미 고른 것이라 틀릴 수가 없다.
     */
    fun configuredModel(userHome: File): String? = null

    /** 깔려 있지 않을 때 알려 줄 설치 방법. */
    val installHint: String

    /**
     * 찾아볼 실행 파일 이름들. 앞의 것이 먼저다.
     *
     * OS를 인자로 받는다 — 값으로 받아야 Windows 개발기에서 mac 분기를 시험할 수 있다.
     */
    fun binaryNames(os: OperatingSystem): List<String>

    /**
     * 이 CLI가 자기 세션을 알리려고 심는 환경 변수인가.
     *
     * IDE가 AI CLI 세션 안에서 실행되면 그 변수들을 물려받는다. 그대로 두고 다시 부르면 자기 안에서
     * 자기를 부른 꼴이 되어 CLI가 거부하거나 이상하게 군다. **실제로 겪은 문제라 남겨 둔 자리다.**
     */
    fun isSessionVariable(name: String): Boolean = false

    /** [model]이 null이거나 비어 있으면 붙이지 않는다. CLI에 정해 둔 사용자 자신의 선택을 따른다. */
    fun invocationOf(executable: String, model: String?): Invocation

    /** 찾지 못했을 때의 문구. 어디서 고치는지까지 적는다. */
    fun notFound(): String =
        "$displayName CLI를 찾을 수 없습니다. $installHint " +
            "이미 설치했다면 터미널의 `${binaryNames(OperatingSystem.current()).first()} --version`이 도는 경로를 " +
            "설정의 실행 파일 경로에 넣으세요."
}

/** AI에게 물어본 결과. 무엇을 쓰려고 물었는지는 이 타입이 알지 않는다. */
sealed interface AiAnswer {

    /** 모델이 낸 글. CLI 봉투는 이미 벗겨 낸 상태다. */
    data class Said(val text: String) : AiAnswer

    /** 애초에 부를 수 없다 — 설치되지 않았거나 경로가 틀렸다. */
    data class NotAvailable(val message: String) : AiAnswer

    /** 불렀는데 잘 되지 않았다 — 시한 초과, 사용량 한도, 로그인 필요. */
    data class Failed(val message: String) : AiAnswer
}
