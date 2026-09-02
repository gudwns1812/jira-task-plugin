package io.github.gudwns1812.jiratask.ui

import com.intellij.openapi.ui.DialogWrapper
import java.awt.Component
import java.awt.Container
import java.awt.Font

/**
 * 한글이 네모(□)로 보이지 않게 글꼴을 손본다.
 *
 * Swing의 입력칸·목록은 룩앤필이 준 **물리 글꼴 하나**로만 글자를 그린다. 그 글꼴에 한글 글리프가
 * 없으면 다른 글꼴을 찾아보지 않고 네모를 그린다 — 실제로 IDE 기본 UI 글꼴이 바뀐 버전에서
 * 이 창들의 한글이 통째로 네모가 되는 것을 확인했다. IDE 자체 커밋 칸이 멀쩡한 이유는 그쪽이
 * 에디터라 별도의 대체 글꼴 규칙을 타기 때문이고, 우리 다이얼로그는 그 규칙 밖에 있다.
 *
 * 논리 글꼴(`Dialog`)은 JDK가 여러 물리 글꼴을 합쳐 만든 것이라 한글이 있는 글꼴로 알아서 넘어간다.
 *
 * **그릴 수 있으면 손대지 않는다.** 사용자가 고른 테마의 글꼴을 이유 없이 갈아치우면
 * 우리 창만 IDE와 따로 노는 모양이 된다.
 */
object HangulFont {

    /** 이 글자를 그릴 수 있는지로 판단한다. 한글 음절 하나면 충분하다. */
    const val PROBE: String = "한글"

    fun canDraw(font: Font): Boolean = font.canDisplayUpTo(PROBE) < 0

    /**
     * [font]로 한글을 그릴 수 없으면 대신 쓸 글꼴, 그릴 수 있으면 null.
     *
     * 굵기와 크기는 그대로 둔다 — 바꾸는 것은 글자 모양을 고르는 방식뿐이다.
     */
    fun fallbackFor(font: Font?, canDraw: (Font) -> Boolean = ::canDraw): Font? {
        if (font == null || canDraw(font)) {
            return null
        }

        return Font(Font.DIALOG, font.style, font.size)
    }
}

/**
 * 이 컴포넌트와 그 안의 모든 자식에서, 한글을 그리지 못하는 글꼴만 골라 바꾼다.
 *
 * 창을 다 만든 뒤에 부른다 — 그 전에는 컴포넌트들이 아직 룩앤필 글꼴을 받지 않았다.
 */
fun Component.applyHangulFallback(canDraw: (Font) -> Boolean = HangulFont::canDraw) {
    HangulFont.fallbackFor(font, canDraw)?.let { font = it }

    if (this is Container) {
        components.forEach { it.applyHangulFallback(canDraw) }
    }
}

/**
 * 창 전체(입력칸과 버튼까지)의 글꼴을 손본다. `init()` 뒤에 부른다.
 *
 * 화면이 없는 곳(테스트)에서는 창틀이 만들어지지 않아 손볼 것도 없다. 그때는 조용히 넘어간다 —
 * 글꼴을 고르는 일이 테스트를 깨뜨릴 이유는 없다.
 */
fun DialogWrapper.applyHangulFallbackToWindow(canDraw: (Font) -> Boolean = HangulFont::canDraw) {
    runCatching { rootPane }.getOrNull()?.applyHangulFallback(canDraw)
}
