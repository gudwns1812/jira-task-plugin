package io.github.gudwns1812.jiratask.infrastructure

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CredentialStoreTest : BasePlatformTestCase() {

    fun `test saved token can be read back for the same base url and email`() {
        val store = PasswordSafeCredentialStore()

        store.save("https://team.atlassian.net", "user@example.com", "secret-token")

        assertEquals("secret-token", store.get("https://team.atlassian.net", "user@example.com"))
    }

    fun `test lookup for an unknown account returns null`() {
        val store = PasswordSafeCredentialStore()

        assertNull(store.get("https://unknown.atlassian.net", "nobody@example.com"))
    }

    fun `test account identity is normalized by trimming slash and case`() {
        val store = PasswordSafeCredentialStore()

        store.save("https://team.atlassian.net/", "USER@example.com", "secret-token")

        assertEquals("secret-token", store.get("https://team.atlassian.net", "user@example.com"))
    }
}
