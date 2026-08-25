package io.github.gudwns1812.jiratask.domain.branch

class BranchNameError(message: String) : Exception(message)

object BranchNames {
    private val STORY_NUMBER_PATTERN = Regex("""^[1-9]\d*$""")
    private val BRANCH_SLUG_PATTERN = Regex("""^[a-z0-9]+(?:-[a-z0-9]+)*$""")
    private val BRANCH_NAME_PATTERN = Regex("""^feat/([1-9]\d*)-[a-z0-9]+(?:-[a-z0-9]+)*$""")

    fun createStoryKey(projectKey: String, storyNumber: String): String {
        if (!STORY_NUMBER_PATTERN.matches(storyNumber)) {
            throw BranchNameError("스토리 번호는 0보다 큰 정수여야 합니다.")
        }

        return "$projectKey-$storyNumber"
    }

    /**
     * [createBranchName]으로 만든 브랜치 이름에서 스토리 번호를 되짚는다. 규칙에 맞지 않으면 null.
     * 현재 브랜치만 보고 스토리를 알아내는 데 쓴다.
     */
    fun storyNumberOf(branchName: String?): String? =
        branchName?.let { BRANCH_NAME_PATTERN.matchEntire(it.trim())?.groupValues?.get(1) }

    fun createBranchName(storyNumber: String, name: String): String {
        if (!STORY_NUMBER_PATTERN.matches(storyNumber)) {
            throw BranchNameError("스토리 번호는 0보다 큰 정수여야 합니다.")
        }

        if (!BRANCH_SLUG_PATTERN.matches(name)) {
            throw BranchNameError("브랜치 이름은 소문자 영문·숫자·하이픈만 사용하고 앞뒤 또는 연속 하이픈을 둘 수 없습니다.")
        }

        return "feat/$storyNumber-$name"
    }
}
