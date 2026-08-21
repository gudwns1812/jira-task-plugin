package io.github.gudwns1812.jiratask.domain.branch

class BranchNameError(message: String) : Exception(message)

object BranchNames {
    private val STORY_NUMBER_PATTERN = Regex("""^[1-9]\d*$""")
    private val BRANCH_SLUG_PATTERN = Regex("""^[a-z0-9]+(?:-[a-z0-9]+)*$""")

    fun createStoryKey(projectKey: String, storyNumber: String): String {
        if (!STORY_NUMBER_PATTERN.matches(storyNumber)) {
            throw BranchNameError("스토리 번호는 0보다 큰 정수여야 합니다.")
        }

        return "$projectKey-$storyNumber"
    }

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
