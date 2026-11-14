// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion-desktop/src/test/.../CliCredentialStoreTest.kt; see NOTICE.
package run.ostrom.app.desktop

import java.io.File
import kotlinx.coroutines.test.runTest
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.services.unlockKeyEntry
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CliCredentialStoreTest {
    private class Fixture(directory: File) {
        val cli = FakeCli(File(directory, "ostrom"))
        val app = MemoryCredentialStore()
        var origin: String? = "https://api.example"
        var reads = 0
        val store = CliCredentialStore(app, CliFiles(cli.directory.path), { server -> reads++; cli.keyring.token(server) }) { id -> if (id == Account.CLI_ACCOUNT_ID) origin else null }
    }

    @Test
    fun theCliAccountReadsTheCliTokenOfItsServerOnly() = withTemporaryDirectory { directory ->
        runTest {
            val f = Fixture(directory)
            assertNull(f.store.read(Account.CLI_ACCOUNT_ID), "Not signed in")
            f.cli.login("https://api.example", "osm-1")
            assertEquals("osm-1", f.store.read(Account.CLI_ACCOUNT_ID))
            assertNull(f.store.read(unlockKeyEntry(Account.CLI_ACCOUNT_ID)), "The CLI holds no unlock key")
            f.cli.login("https://api.other.example", "osm-2")
            assertFailsWith<IllegalStateException>("Never the other server's token, never a sign-out") { f.store.read(Account.CLI_ACCOUNT_ID) }
            f.cli.logout("https://api.other.example")
            f.cli.logout("https://api.example")
            assertNull(f.store.read(Account.CLI_ACCOUNT_ID))
        }
    }

    @Test
    fun theSecretStoreIsAskedOncePerVersionOfTheFiles() = withTemporaryDirectory { directory ->
        runTest {
            val f = Fixture(directory)
            f.cli.login("https://api.example", "osm-1")
            repeat(3) { f.store.read(Account.CLI_ACCOUNT_ID) }
            assertEquals(1, f.reads)
            f.cli.login("https://api.example", "osm-3")
            assertEquals("osm-3", f.store.read(Account.CLI_ACCOUNT_ID))
            assertEquals(2, f.reads)
        }
    }

    @Test
    fun theAppNeverWritesOrDeletesTheCliToken() = withTemporaryDirectory { directory ->
        runTest {
            val f = Fixture(directory)
            f.cli.login("https://api.example", "osm-1")
            assertFailsWith<IllegalStateException> { f.store.write(Account.CLI_ACCOUNT_ID, "osm-x") }
            f.store.delete(Account.CLI_ACCOUNT_ID)
            f.store.delete(unlockKeyEntry(Account.CLI_ACCOUNT_ID))
            assertEquals(mapOf("https://api.example" to "osm-1"), f.cli.tokens)
            assertEquals("osm-1", f.store.read(Account.CLI_ACCOUNT_ID))
        }
    }

    @Test
    fun otherAccountsUseTheAppKeyring() = withTemporaryDirectory { directory ->
        runTest {
            val f = Fixture(directory)
            f.store.write("a1", "osm-app")
            assertEquals("osm-app", f.app.read("a1"))
            assertEquals("osm-app", f.store.read("a1"))
            f.store.delete("a1")
            assertNull(f.app.read("a1"))
        }
    }
}
