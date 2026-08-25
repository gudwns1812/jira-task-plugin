package io.github.gudwns1812.jiratask.ui.issue

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.CalendarView
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import javax.swing.JComponent

/**
 * 기한을 달력에서 고르는 작은 대화상자. 플랫폼의 [CalendarView]를 그대로 쓰고,
 * 고른 날짜를 `YYYY-MM-DD`로 돌려준다(입력 칸에 직접 타이핑하는 길도 그대로 열어둔다).
 */
class DueDateDialog(initial: LocalDate?) : DialogWrapper(false) {

    private val calendarView = CalendarView().apply {
        date = Date.from((initial ?: LocalDate.now()).atStartOfDay(ZoneId.systemDefault()).toInstant())
    }

    init {
        title = "기한 선택"
        setOKButtonText("선택")
        init()
        calendarView.registerEnterHandler { doOKAction() }
    }

    override fun createCenterPanel(): JComponent = calendarView

    /** 대화상자를 띄우고 고른 날짜를 돌려준다. 취소하면 null. */
    fun choose(): LocalDate? = if (showAndGet()) selectedDate() else null

    fun selectedDate(): LocalDate =
        calendarView.date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
}
