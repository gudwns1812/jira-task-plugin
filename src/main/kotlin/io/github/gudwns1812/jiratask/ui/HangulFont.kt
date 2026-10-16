package io.github.gudwns1812.jiratask.ui

import com.intellij.openapi.ui.DialogWrapper
import java.awt.Component
import java.awt.Container
import java.awt.Font

object HangulFont {
    const val PROBE: String = "한글"

    fun canDraw(font: Font): Boolean = font.canDisplayUpTo(PROBE) < 0

    fun fallbackFor(font: Font?, canDraw: (Font) -> Boolean = ::canDraw): Font? {
        if (font == null || canDraw(font)) {
            return null
        }

        return Font(Font.DIALOG, font.style, font.size)
    }
}

fun Component.applyHangulFallback(canDraw: (Font) -> Boolean = HangulFont::canDraw) {
    HangulFont.fallbackFor(font, canDraw)?.let { font = it }

    if (this is Container) {
        components.forEach { it.applyHangulFallback(canDraw) }
    }
}

fun DialogWrapper.applyHangulFallbackToWindow(canDraw: (Font) -> Boolean = HangulFont::canDraw) {
    runCatching { rootPane }.getOrNull()?.applyHangulFallback(canDraw)
}
