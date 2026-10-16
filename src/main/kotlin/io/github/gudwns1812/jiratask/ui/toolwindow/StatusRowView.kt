package io.github.gudwns1812.jiratask.ui.toolwindow

import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import java.awt.Font
import javax.swing.JComponent

sealed interface StatusRowView {

    val component: JComponent

    fun show(row: StatusRow)

    class Text(private val monospace: Boolean) : StatusRowView {
        private val label = JBLabel().apply { setCopyable(true) }

        override val component: JComponent get() = label

        override fun show(row: StatusRow) {
            label.text = row.value
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
