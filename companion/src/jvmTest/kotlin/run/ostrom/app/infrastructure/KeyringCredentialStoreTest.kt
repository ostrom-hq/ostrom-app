// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure

import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import kotlinx.coroutines.test.runTest
import run.ostrom.app.infrastructure.credentials.KeyringBackend
import run.ostrom.app.infrastructure.credentials.KeyringCredentialStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.github.javakeyring.internal.KeyringBackend as JavaKeyringInternalBackend

/** Tokens in the operating system's keyring, with a fake keyring: what is kept where, and what a refusing keyring does. */
class KeyringCredentialStoreTest {
    private class FakeKeyring : KeyringBackend {
        val entries = mutableMapOf<Pair<String, String>, String>()
        var failGet: Exception? = null
        var failSet: Exception? = null
        var failDelete: Exception? = null
        val deleted = mutableListOf<Pair<String, String>>()
        override fun get(service: String, account: String): String? = failGet?.let { throw it } ?: entries[service to account]
        override fun set(service: String, account: String, secret: String) {
            failSet?.let { throw it }
            entries[service to account] = secret
        }
        override fun delete(service: String, account: String) {
            deleted += service to account
            failDelete?.let { throw it }
            entries.remove(service to account)
        }
    }

    private val keyring = FakeKeyring()
    private val store = KeyringCredentialStore(keyring)

    @Test fun aWrittenTokenLivesInTheKeyringUnderTheAppsServiceAndIsPersistent() = runTest {
        store.write("a1", "osm_1")
        assertEquals("osm_1", keyring.entries[KeyringCredentialStore.SERVICE to "a1"])
        assertEquals("osm_1", store.read("a1"))
        assertTrue(store.persistent)
    }

    @Test fun aTokenTheKeyringDoesNotHaveReadsAsNone() = runTest {
        assertNull(store.read("a1"))
    }

    @Test fun aKeyringThatFailsToAnswerOnReadThrowsInsteadOfSigningTheAccountOut() = runTest {
        keyring.failGet = IllegalStateException("locked")
        assertFailsWith<IllegalStateException> { store.read("a1") }
    }

    @Test fun aRefusedWriteKeepsTheTokenInMemoryForThisRunAndIsNoLongerPersistent() = runTest {
        keyring.entries[KeyringCredentialStore.SERVICE to "a1"] = "osm_old"
        keyring.failSet = IllegalStateException("locked")
        store.write("a1", "osm_new")
        assertEquals("osm_new", store.read("a1"), "memory is read first, the replaced token never comes back")
        assertFalse(store.persistent)
        assertEquals(listOf(KeyringCredentialStore.SERVICE to "a1"), keyring.deleted, "the older entry is dropped when the keyring allows it")
        assertNull(keyring.entries[KeyringCredentialStore.SERVICE to "a1"])
    }

    @Test fun whenTheOlderEntryCannotBeDroppedEitherTheWriteStillSucceedsInMemory() = runTest {
        keyring.failSet = IllegalStateException("locked")
        keyring.failDelete = IllegalStateException("locked")
        store.write("a1", "osm_new")
        assertEquals("osm_new", store.read("a1"))
    }

    @Test fun aLaterSuccessfulWriteReturnsTheAccountToTheKeyring() = runTest {
        keyring.failSet = IllegalStateException("locked")
        store.write("a1", "osm_mem")
        assertFalse(store.persistent)
        keyring.failSet = null
        store.write("a1", "osm_key")
        assertTrue(store.persistent)
        assertEquals("osm_key", keyring.entries[KeyringCredentialStore.SERVICE to "a1"])
        assertEquals("osm_key", store.read("a1"), "the memory copy was dropped")
    }

    @Test fun deletingRemovesTheTokenEverywhereAndTheAccountIsPersistentAgain() = runTest {
        keyring.failSet = IllegalStateException("locked")
        store.write("a1", "osm_mem")
        keyring.failSet = null
        keyring.deleted.clear()
        store.delete("a1")
        assertNull(store.read("a1"))
        assertTrue(store.persistent)
        assertEquals(listOf(KeyringCredentialStore.SERVICE to "a1"), keyring.deleted)
    }

    @Test fun aRefusedDeleteThrowsButTheMemoryCopyIsAlreadyGone() = runTest {
        store.write("a1", "osm_1")
        keyring.failDelete = IllegalStateException("locked")
        assertFailsWith<IllegalStateException> { store.delete("a1") }
    }

    @Test fun withoutAKeyringTokensStayInMemoryAndAreNotPersistent() = runTest {
        val memoryOnly = KeyringCredentialStore(null)
        assertFalse(memoryOnly.persistent)
        assertNull(memoryOnly.read("a1"))
        memoryOnly.write("a1", "osm_1")
        assertEquals("osm_1", memoryOnly.read("a1"))
        assertFalse(memoryOnly.persistent)
        memoryOnly.delete("a1")
        assertNull(memoryOnly.read("a1"))
    }

    @Test fun aCustomServiceNameIsUsed() = runTest {
        KeyringCredentialStore(keyring, service = "other").write("a1", "osm_1")
        assertEquals("osm_1", keyring.entries["other" to "a1"])
    }

    @Test fun openingTheStoreNeverCrashesWhateverTheSystemOffers() = runTest {
        // The OS keyring or none (a Linux desktop without Secret Service): the store is built either way.
        val opened = KeyringCredentialStore.open()
        assertEquals(KeyringCredentialStore.openBackend() != null, opened.persistent)
    }

    /** The java-keyring adapter, built around a scripted java-keyring backend (its constructors are not public). */
    private class JavaBackend : JavaKeyringInternalBackend {
        var error: String? = "unset"
        var password: String? = "secret"
        val set = mutableListOf<Triple<String, String, String>>()
        override fun getPassword(service: String, account: String): String? = error?.takeIf { it != "unset" }?.let { throw PasswordAccessException(it) } ?: password
        override fun setPassword(service: String, account: String, password: String) { set += Triple(service, account, password) }
        override fun deletePassword(service: String, account: String) { error?.takeIf { it != "unset" }?.let { throw PasswordAccessException(it) } }
        override fun close() = Unit
    }

    private fun adapter(backend: JavaBackend): KeyringBackend {
        val keyringConstructor = Keyring::class.java.getDeclaredConstructor(JavaKeyringInternalBackend::class.java).apply { isAccessible = true }
        val adapter = Class.forName("run.ostrom.app.infrastructure.credentials.JavaKeyringBackend").getDeclaredConstructor(Keyring::class.java)
            .apply { isAccessible = true }
        return adapter.newInstance(keyringConstructor.newInstance(backend)) as KeyringBackend
    }

    @Test fun theAdapterPassesPasswordsThroughToJavaKeyring() {
        val backend = JavaBackend()
        val adapter = adapter(backend)
        assertEquals("secret", adapter.get("svc", "acc"))
        adapter.set("svc", "acc", "pw")
        assertEquals(listOf(Triple("svc", "acc", "pw")), backend.set)
        adapter.delete("svc", "acc")
    }

    @Test fun theAdapterReadsEachBackendsMissingEntryPhraseAsNoToken() {
        val backend = JavaBackend()
        val adapter = adapter(backend)
        for (phrase in listOf("Password not Found", "The specified item could not be found in the keychain.", "No stored credentials match")) {
            backend.error = phrase
            assertNull(adapter.get("svc", "acc"), phrase)
            adapter.delete("svc", "acc")
        }
    }

    @Test fun theAdapterReportsARefusedAccessInsteadOfReadingItAsNoToken() {
        val backend = JavaBackend()
        val adapter = adapter(backend)
        backend.error = "Keyring is locked"
        assertEquals("Keyring is locked", assertFailsWith<PasswordAccessException> { adapter.get("svc", "acc") }.message)
        assertNotNull(assertFailsWith<PasswordAccessException> { adapter.delete("svc", "acc") })
    }

    @Test fun anErrorWithoutAMessageIsARefusalToo() {
        val backend = JavaBackend()
        backend.error = ""
        val adapter = adapter(backend)
        assertFailsWith<PasswordAccessException> { adapter.get("svc", "acc") }
        assertFailsWith<PasswordAccessException> { adapter.delete("svc", "acc") }
    }
}
