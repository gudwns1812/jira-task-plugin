package io.github.gudwns1812.jiratask.ui.mr

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import io.github.gudwns1812.jiratask.ui.applyHangulFallbackToWindow
import javax.swing.JComponent

class MultiPickDialog<T : Any>(
    project: Project?,
    dialogTitle: String,
    private val items: List<T>,
    selected: List<T>,
    private val labelOf: (T) -> String,
    private val search: ((String) -> List<T>)? = null,
    private val single: Boolean = false,
    private val background: (() -> Unit) -> Unit = {
        ApplicationManager.getApplication().executeOnPooledThread(Runnable(it))
    },
    private val onEdt: (() -> Unit) -> Unit = {
        ApplicationManager.getApplication().invokeLater(Runnable(it), ModalityState.any())
    },
) : DialogWrapper(project, false) {

    private val list = CheckBoxList<T>()

    private val searchField = JBTextField()

    private val noticeLabel = JBLabel()

    private var shown: List<T> = emptyList()

    private val chosen: MutableSet<T> = LinkedHashSet(selected)

    init {
        title = dialogTitle
        setOKButtonText("확인")
        init()
        applyHangulFallbackToWindow()

        searchField.addActionListener { runSearch() }
        if (single) {
            list.setCheckBoxListListener { index, value ->
                if (value) {
                    (0 until list.itemsCount).filter { it != index }.forEach { list.setItemSelected(list.getItemAt(it), false) }
                }
            }
        }
        show(items)
    }

    override fun createCenterPanel(): JComponent = panel {
        if (search != null) {
            row("찾기:") {
                cell(searchField).columns(COLUMNS_MEDIUM)
                button("검색") { runSearch() }
            }
        }
        row { cell(JBScrollPane(list)).resizableColumn() }
        row { cell(noticeLabel) }
    }

    override fun getPreferredFocusedComponent(): JComponent = if (search != null) searchField else list

    fun picked(): List<T> {
        collect()
        return chosen.toList()
    }

    fun noticeText(): String = noticeLabel.text.orEmpty()

    fun searchWith(query: String) {
        searchField.text = query
        runSearch()
    }

    fun setSearchText(query: String) {
        searchField.text = query
    }

    fun pressEnterInSearch() {
        searchField.postActionEvent()
    }

    private fun runSearch() {
        val query = searchField.text.trim()
        val ask = search ?: return
        if (query.isEmpty()) {
            show(items)
            return
        }

        collect()
        noticeLabel.text = SEARCHING
        background {
            val found = runCatching { ask(query) }.getOrDefault(emptyList())
            onEdt {
                noticeLabel.text = if (found.isEmpty()) noneFound(query) else ""
                show(found)
            }
        }
    }

    private fun show(candidates: List<T>) {
        collect()
        shown = (chosen + candidates).distinct()

        list.clear()
        shown.forEach { list.addItem(it, labelOf(it), it in chosen) }
    }

    private fun collect() {
        shown.forEachIndexed { index, item ->
            if (index < list.itemsCount && list.isItemSelected(index)) {
                chosen += item
            } else {
                chosen -= item
            }
        }
    }

    companion object {
        const val SEARCHING: String = "찾는 중…"

        fun noneFound(query: String): String = "'$query'에 해당하는 사람을 찾지 못했습니다."
    }
}
