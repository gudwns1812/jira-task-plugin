package io.github.gudwns1812.jiratask.ui.toolwindow

import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import java.awt.Font
import javax.swing.JComponent

/**
 * 상태 한 줄의 값을 그리는 컴포넌트. 값 종류([StatusRow.Kind])마다 다르게 그린다.
 *
 * - `TEXT`/`CODE`: 복사할 수 있는 라벨(고정폭 여부만 다름)
 * - `LINK`: 클릭하면 브라우저로 여는 링크. 값이 없으면 링크를 비활성으로 두어 회색 안내 문구가 된다
 */
sealed interface StatusRowView {

    val component: JComponent

    fun show(row: StatusRow)

    class Text(private val monospace: Boolean) : StatusRowView {
        private val label = JBLabel().apply { setCopyable(true) }

        override val component: JComponent get() = label

        override fun show(row: StatusRow) {
            label.text = row.value
            // 고정폭은 실제 값(키·번호·브랜치)에만 쓴다. 안내 문구는 일반 폰트가 읽기 좋다.
            label.font = if (monospace && !row.isPlaceholder) monospacedFont() else JBFont.label()
            label.foreground = if (row.isPlaceholder) {
                NamedColorUtil.getInactiveTextColor()
            } else {
                UIUtil.getLabelForeground()
            }
        }

        private fun monospacedFont(): Font = JBFont.create(Font(Font.MONOSPACED, Font.PLAIN, JBFont.label().size))
    }

    class Link(private val browse: (String) -> Unit) : StatusRowView {
        private var target: String? = null

        private val link = ActionLink("") { target?.let(browse) }

        override val component: JComponent get() = link

        override fun show(row: StatusRow) {
            target = row.value.takeUnless { row.isPlaceholder }
            link.text = row.value
            // 열 곳이 없으면 링크처럼 보이지 않게 비활성으로 둔다(회색 + 클릭 불가).
            link.isEnabled = target != null
        }
    }

    companion object {
        fun of(kind: StatusRow.Kind, browse: (String) -> Unit): StatusRowView = when (kind) {
            StatusRow.Kind.LINK -> Link(browse)
            StatusRow.Kind.CODE -> Text(monospace = true)
            StatusRow.Kind.TEXT -> Text(monospace = false)
        }
    }
}
