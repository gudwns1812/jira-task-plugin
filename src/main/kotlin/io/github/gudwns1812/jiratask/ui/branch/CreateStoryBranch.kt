package io.github.gudwns1812.jiratask.ui.branch

import com.intellij.notification.NotificationType
import io.github.gudwns1812.jiratask.application.CreateBranchResult
import io.github.gudwns1812.jiratask.domain.branch.BranchNameError
import io.github.gudwns1812.jiratask.domain.branch.BranchNames

/**
 * "Create Story Branch" 다이얼로그의 입력값. 검증은 도메인 규칙([BranchNames])을 그대로 쓴다 —
 * 입력 중 안내와 실행 시 판단이 어긋나지 않게 하기 위해서다.
 */
data class BranchFormInput(val storyNumber: String, val name: String) {

    fun problem(): String? = try {
        BranchNames.createBranchName(storyNumber.trim(), name.trim())
        null
    } catch (error: BranchNameError) {
        error.message
    }

    fun trimmed(): BranchFormInput = BranchFormInput(storyNumber.trim(), name.trim())
}

/** 알림으로 보여줄 결과 문구와 심각도. CLI의 종료 코드(0/4/그 외)에 대응한다. */
data class BranchReport(val type: NotificationType, val message: String)

/** [CreateBranchResult]를 알림 한 줄로 옮긴다. CLI가 콘솔에 찍던 문구와 같은 내용이다. */
object BranchReports {

    fun of(result: CreateBranchResult): BranchReport = when (result) {
        is CreateBranchResult.Success -> BranchReport(
            NotificationType.INFORMATION,
            if (result.transitioned) {
                "브랜치를 만들고 Jira 스토리를 진행 중으로 전환했습니다: ${result.branchName} (${result.storyKey})"
            } else {
                "브랜치를 만들었습니다: ${result.branchName} (${result.storyKey}는 이미 진행 중)"
            },
        )
        is CreateBranchResult.PartialSuccess -> BranchReport(
            NotificationType.WARNING,
            "브랜치는 만들었지만 Jira 상태 전환에 실패했습니다: ${result.branchName} (${result.storyKey}) - ${result.message}",
        )
        is CreateBranchResult.Failure -> BranchReport(
            NotificationType.ERROR,
            "브랜치 생성 실패: ${result.message}",
        )
    }
}
