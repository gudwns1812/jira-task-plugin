package io.github.gudwns1812.jiratask.domain.branch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BranchNameTest {

    @Test
    fun `builds jira key and branch name from project key and story number`() {
        assertEquals("S15P11A108-100", BranchNames.createStoryKey("S15P11A108", "100"))
        assertEquals("feat/100-email-signup", BranchNames.createBranchName("100", "email-signup"))
    }

    @Test
    fun `rejects invalid story numbers`() {
        for (storyNumber in listOf("0", "-1", "100a", " 100")) {
            assertThrows(BranchNameError::class.java) { BranchNames.createStoryKey("S15P11A108", storyNumber) }
        }
    }

    @Test
    fun `rejects invalid branch slugs`() {
        for (name in listOf("Email", "email signup", "-email", "email-", "email--signup")) {
            assertThrows(BranchNameError::class.java) { BranchNames.createBranchName("100", name) }
        }
    }

    @Test
    fun `rejects invalid story number even when the slug is valid`() {
        assertThrows(BranchNameError::class.java) { BranchNames.createBranchName("0", "signup") }
    }
}
