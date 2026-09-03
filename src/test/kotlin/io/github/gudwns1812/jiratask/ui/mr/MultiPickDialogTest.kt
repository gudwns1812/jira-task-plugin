package io.github.gudwns1812.jiratask.ui.mr

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.gudwns1812.jiratask.domain.mr.GitLabUser

/** 리뷰어·라벨을 고르는 작은 창. 확인하려는 것은 "무엇을 고른 채로 남는가"다. */
class MultiPickDialogTest : BasePlatformTestCase() {

    private val me = GitLabUser(42, "gudwns1812", "박형준")
    private val jimin = GitLabUser(7, "jimin", "김지민")
    private val hyungjun = GitLabUser(9, "hyungjun", "이형준")

    private val asked = mutableListOf<String>()

    private fun dialogOf(
        items: List<GitLabUser> = listOf(me),
        selected: List<GitLabUser> = emptyList(),
        found: List<GitLabUser> = listOf(jimin, hyungjun),
    ) = MultiPickDialog(
        project = project,
        dialogTitle = "리뷰어 고르기",
        items = items,
        selected = selected,
        labelOf = GitLabUser::label,
        search = { query ->
            asked += query
            found
        },
        background = { it() },
        onEdt = { it() },
    )

    fun `test enter in the search field searches instead of closing the window`() {
        // Enter가 창의 기본 버튼으로 흘러가면, 이름을 치고 Enter를 누른 순간 창이 닫힌다.
        val dialog = dialogOf()
        try {
            dialog.searchWith("")
            asked.clear()

            dialog.setSearchText("지민")
            dialog.pressEnterInSearch()

            assertEquals(listOf("지민"), asked)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    fun `test keeps what was already picked when the search brings new people`() {
        val dialog = dialogOf(selected = listOf(me))
        try {
            dialog.searchWith("지민")

            // 검색어를 바꿔 가며 여러 명을 고르는 것이 이 창의 쓰임이다.
            assertTrue(dialog.picked().contains(me))
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    fun `test says so when the search found nobody`() {
        val dialog = dialogOf(found = emptyList())
        try {
            dialog.searchWith("없는사람")

            assertEquals(MultiPickDialog.noneFound("없는사람"), dialog.noticeText())
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    fun `test an empty query goes back to the list it started with`() {
        val dialog = dialogOf()
        try {
            dialog.searchWith("   ")

            assertTrue("빈 검색어로 GitLab에 묻지 않는다", asked.isEmpty())
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    override fun tearDown() {
        try {
            asked.clear()
        } finally {
            super.tearDown()
        }
    }
}
