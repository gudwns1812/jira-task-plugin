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

/**
 * 여러 개를 골라 담는 작은 창. 리뷰어와 라벨이 함께 쓴다.
 *
 * 고른 것은 **언제나 맨 위**에 둔다. 사람이 수백 명인 프로젝트에서 목록 가운데 흩어져 있으면
 * 지금 무엇을 골랐는지 확인하려고 스크롤을 뒤져야 한다.
 *
 * [search]가 있으면 검색 칸을 둔다. 프로젝트 사람이 많아 첫 쪽에 없는 사람을 찾는 길이다 —
 * 라벨은 한 번에 다 읽어 오므로 검색 칸이 없다.
 */
class MultiPickDialog<T : Any>(
    project: Project?,
    dialogTitle: String,
    private val items: List<T>,
    selected: List<T>,
    private val labelOf: (T) -> String,
    /** 목록 밖의 것을 GitLab에 물어보는 방법. 네트워크를 타므로 백그라운드에서 부른다. */
    private val search: ((String) -> List<T>)? = null,
    /**
     * 하나만 고르게 한다. 담당자가 그렇다 — GitLab 무료 요금제는 담당자를 한 명만 받는다.
     * 새로 고르면 앞의 선택이 풀린다.
     */
    private val single: Boolean = false,
    private val background: (() -> Unit) -> Unit = {
        ApplicationManager.getApplication().executeOnPooledThread(Runnable(it))
    },
    /** 모달 창이라 [ModalityState.any]가 아니면 결과가 창 닫힌 뒤에 반영된다. */
    private val onEdt: (() -> Unit) -> Unit = {
        ApplicationManager.getApplication().invokeLater(Runnable(it), ModalityState.any())
    },
) : DialogWrapper(project, false) {

    private val list = CheckBoxList<T>()

    private val searchField = JBTextField()

    private val noticeLabel = JBLabel()

    /** 화면에 올린 것들. 검색으로 더 받아 오면 여기에 쌓인다. */
    private var shown: List<T> = emptyList()

    private val chosen: MutableSet<T> = LinkedHashSet(selected)

    init {
        title = dialogTitle
        setOKButtonText("확인")
        init()
        applyHangulFallbackToWindow()

        // 검색 칸에서 Enter는 찾기다. 듣는 사람이 없으면 Enter가 그대로 창의 기본 버튼(확인)으로
        // 흘러가, 이름을 치고 Enter를 누른 순간 창이 닫힌다.
        searchField.addActionListener { runSearch() }
        if (single) {
            // 체크는 언제나 하나만 남긴다. 목록을 다시 그리지 않고 화면에서 바로 푼다.
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

    /** 고른 것들. 창을 닫은 뒤에 읽는다. */
    fun picked(): List<T> {
        collect()
        return chosen.toList()
    }

    /** 지금 화면이 무엇을 말하고 있는지. 창을 뒤지지 않고 확인하려고 열어 둔다. */
    fun noticeText(): String = noticeLabel.text.orEmpty()

    fun searchWith(query: String) {
        searchField.text = query
        runSearch()
    }

    /** 검색 칸에 글자를 넣기만 한다. 찾지는 않는다. */
    fun setSearchText(query: String) {
        searchField.text = query
    }

    /** 검색 칸에서 Enter를 누른 것과 같다. 창을 띄우지 않고 그 배선을 확인하려고 열어 둔다. */
    fun pressEnterInSearch() {
        searchField.postActionEvent()
    }

    /**
     * 검색 결과를 목록에 더한다. **이미 고른 것은 지우지 않는다** — 검색어를 바꿔 가며 여러 명을
     * 고르는 것이 이 창의 쓰임인데, 새 결과가 앞의 선택을 밀어내면 처음부터 다시 골라야 한다.
     */
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

    /** 고른 것을 맨 위에, 나머지를 그 아래에. 같은 것이 두 번 나오지 않게 한다. */
    private fun show(candidates: List<T>) {
        collect()
        shown = (chosen + candidates).distinct()

        list.clear()
        shown.forEach { list.addItem(it, labelOf(it), it in chosen) }
    }

    /** 화면의 체크 상태를 [chosen]에 옮긴다. 목록을 다시 그리기 전에 반드시 부른다. */
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
