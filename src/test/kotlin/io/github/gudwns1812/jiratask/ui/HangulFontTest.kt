package io.github.gudwns1812.jiratask.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.awt.Font
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * 한글을 못 그리는 글꼴만 골라 바꾸는 규칙.
 *
 * 실제 글꼴 대신 "그릴 수 있는가"를 가짜로 넘겨 시험한다 — 어떤 글꼴이 깔려 있는지는
 * 기계마다 다르고, 확인하려는 것은 글꼴 목록이 아니라 판단 규칙이다.
 */
class HangulFontTest {

    private val draws: (Font) -> Boolean = { true }
    private val doesNotDraw: (Font) -> Boolean = { false }

    @Test
    fun `leaves a font that can already draw hangul alone`() {
        val font = Font("Malgun Gothic", Font.BOLD, 14)

        assertNull(HangulFont.fallbackFor(font, draws))
    }

    @Test
    fun `falls back to a font that composes hangul`() {
        val fallback = HangulFont.fallbackFor(Font("Inter", Font.PLAIN, 13), doesNotDraw)

        assertNotNull(fallback)
        // 논리 글꼴이라야 JDK가 한글 있는 글꼴로 넘어간다. 물리 글꼴은 없으면 네모를 그린다.
        assertEquals(Font.DIALOG, fallback!!.family)
    }

    @Test
    fun `keeps the weight and the size it was given`() {
        val fallback = HangulFont.fallbackFor(Font("Inter", Font.BOLD, 17), doesNotDraw)!!

        assertEquals(Font.BOLD, fallback.style)
        assertEquals(17, fallback.size)
    }

    @Test
    fun `has nothing to say about a component without a font`() {
        assertNull(HangulFont.fallbackFor(null, doesNotDraw))
    }

    @Test
    fun `fixes every child of the panel, not just the panel`() {
        val panel = JPanel()
        val label = JLabel("커밋 메시지")
        val inner = JPanel().apply { add(JLabel("연결된 작업")) }
        panel.add(label)
        panel.add(inner)
        panel.font = Font("Inter", Font.PLAIN, 13)
        label.font = Font("Inter", Font.PLAIN, 13)

        panel.applyHangulFallback(doesNotDraw)

        assertEquals(Font.DIALOG, panel.font.family)
        assertEquals(Font.DIALOG, label.font.family)
        assertEquals(Font.DIALOG, (inner.components.single() as JLabel).font.family)
    }

    @Test
    fun `does not touch the theme's font when it draws hangul`() {
        val panel = JPanel()
        val chosen = Font("Malgun Gothic", Font.PLAIN, 13)
        panel.font = chosen

        panel.applyHangulFallback(draws)

        assertSame("사용자가 고른 테마 글꼴을 이유 없이 갈아치우지 않는다", chosen, panel.font)
    }
}
