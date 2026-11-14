// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion-desktop/src/test/.../CliAccountSyncTest.kt; see NOTICE.
package run.ostrom.app.desktop

import com.russhwolf.settings.MapSettings
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Organization
import run.ostrom.app.domain.models.SignInMethod
import run.ostrom.app.domain.repositories.Member
import run.ostrom.app.domain.services.unlockKeyEntry
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.settings.SettingsAccountRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The CLI's account follows the CLI's files: added, described again, signed out, removed, merged. */
class CliAccountSyncTest {
    private class Fixture(directory: File) {
        val cli = FakeCli(File(directory, "ostrom"))
        val accounts = SettingsAccountRepository(MapSettings())
        val credentials = MemoryCredentialStore()
        val auth = RecordingAuth().apply {
            members["osm-1"] = Member("ada", Organization("o1", "Acme"), "m1")
            members["osm-2"] = Member("ada", Organization("o2", "Globex"), "m2")
            members["osm-grace"] = Member("grace", null)
        }
        val clock = TestClock()
        val sync = CliAccountSync(CliFiles(cli.directory.path), cli.keyring, accounts, credentials, auth, clock, pollMillis = 10, retryMillis = 60_000)

        fun cliAccount() = accounts.accounts.value.firstOrNull { it.usedByCli }
    }

    @Test
    fun aTerminalLoginAddsTheCliAccountAndKeepsTheActiveOne() = withTemporaryDirectory { directory ->
        runTest {
            val f = Fixture(directory)
            f.accounts.save(Account("a1", "https://other.example", "grace"))
            f.sync.sync()
            assertNull(f.cliAccount(), "No CLI folder, no CLI account")
            f.cli.login("https://api.example", "osm-1")
            f.sync.sync()
            val account = f.cliAccount()!!
            assertEquals("https://api.example", account.origin)
            assertEquals("ada", account.login)
            assertEquals("o1", account.organizationId)
            assertEquals("Acme", account.organizationName)
            assertEquals("m1", account.memberId)
            assertEquals("2.0.0", account.serverVersion)
            assertEquals(SignInMethod.DEVICE, account.method)
            assertNull(account.expiresAt)
            assertEquals("a1", f.accounts.activeAccountId.value, "The account the person was looking at stays the active one")
        }
    }

    @Test
    fun theFilesAreOnlyReadAgainWhenTheyChange() = withTemporaryDirectory { directory ->
        runTest {
            val f = Fixture(directory)
            f.cli.login("https://api.example", "osm-1")
            f.sync.sync()
            f.sync.sync()
            assertEquals(listOf("me https://api.example osm-1"), f.auth.calls)
        }
    }

    @Test
    fun aLoginToAnotherServerDescribesTheAccountAgain() = withTemporaryDirectory { directory ->
        runTest {
            val f = Fixture(directory)
            f.cli.login("https://api.example", "osm-1")
            f.sync.sync()
            f.cli.login("https://api.other.example/", "osm-2")
            f.sync.sync()
            val account = f.cliAccount()!!
            assertEquals("https://api.other.example", account.origin, "The server without its trailing slash, as the CLI keys it")
            assertEquals("o2", account.organizationId)
            assertEquals("m2", account.memberId)
            assertTrue("release cli" in f.auth.calls, "The previous server's client is closed")
        }
    }

    @Test
    fun aLogoutRemovesTheAccount() = withTemporaryDirectory { directory ->
        runTest {
            val f = Fixture(directory)
            f.cli.login("https://api.example", "osm-1")
            f.sync.sync()
            f.cli.logout("https://api.example")
            f.sync.sync()
            assertNull(f.cliAccount())
            assertTrue("release cli" in f.auth.calls)
        }
    }

    @Test
    fun aRefusedTokenListsTheAccountSignedOutUntilTheCliSignsInAgain() = withTemporaryDirectory { directory ->
        runTest {
            val f = Fixture(directory)
            f.cli.login("https://api.example", "osm-1")
            f.sync.sync()
            f.auth.failure = AppException.Unauthorized()
            f.cli.login("https://api.example", "osm-1")
            f.sync.sync()
            assertFalse(f.cliAccount()!!.signedIn)
            f.clock.now += 120_000
            f.sync.sync()
            assertEquals(2, f.auth.calls.count { it.startsWith("me ") }, "Not asked again before the files change")
            f.auth.failure = null
            f.cli.login("https://api.example", "osm-1")
            f.sync.sync()
            assertTrue(f.cliAccount()!!.signedIn)
        }
    }

    @Test
    fun aRefusedTokenOnAnotherServerMovesTheAccountThereSignedOut() = withTemporaryDirectory { directory ->
        runTest {
            val f = Fixture(directory)
            f.cli.login("https://api.example", "osm-1")
            f.sync.sync()
            f.auth.failure = AppException.Unauthorized()
            f.cli.login("https://api.other.example", "osm-dead")
            f.sync.sync()
            val account = f.cliAccount()!!
            assertEquals("https://api.other.example", account.origin, "Never the previous server, whose token the CLI no longer uses")
            assertFalse(account.signedIn)
            assertNull(account.organizationId)
            assertTrue("release cli" in f.auth.calls)
        }
    }

    @Test
    fun aCredentialsFileCaughtMidWriteChangesNothing() = withTemporaryDirectory { directory ->
        runTest {
            val f = Fixture(directory)
            f.cli.login("https://api.example", "osm-1")
            f.sync.sync()
            File(f.cli.directory, "credentials.json").writeText("{ \"https://api.exa")
            f.sync.sync()
            assertTrue(f.cliAccount()!!.signedIn, "Not a sign-out")
            f.cli.logout("https://api.example")
            f.sync.sync()
            assertNull(f.cliAccount(), "Its next version is read")
        }
    }

    @Test
    fun anUnreachableServerIsAskedAgainAfterTheDelay() = withTemporaryDirectory { directory ->
        runTest {
            val f = Fixture(directory)
            f.auth.failure = AppException.Unreachable()
            f.cli.login("https://api.example", "osm-1")
            f.sync.sync()
            assertNull(f.cliAccount())
            f.auth.failure = null
            f.sync.sync()
            assertNull(f.cliAccount(), "Not before the delay")
            f.clock.now += 60_000
            f.sync.sync()
            assertEquals("ada", f.cliAccount()?.login)
        }
    }

    @Test
    fun aLockedSecretStoreChangesNothingAndIsAskedAgainAfterTheDelay() = withTemporaryDirectory { directory ->
        runTest {
            val f = Fixture(directory)
            f.cli.login("https://api.example", "osm-1")
            f.sync.sync()
            f.cli.locked = true
            f.cli.login("https://api.example", "osm-1")
            f.sync.sync()
            assertTrue(f.cliAccount()!!.signedIn, "A locked store is not a sign-out")
            f.cli.locked = false
            f.clock.now += 60_000
            f.sync.sync()
            assertEquals(2, f.auth.calls.count { it.startsWith("me ") })
        }
    }

    @Test
    fun aSignInWithoutItsTokenIsNoSignIn() = withTemporaryDirectory { directory ->
        runTest {
            val f = Fixture(directory)
            f.cli.login("https://api.example", "osm-1")
            f.cli.tokens.clear()
            f.sync.sync()
            assertNull(f.cliAccount())
            assertTrue(f.auth.calls.isEmpty(), "No token, nothing sent")
        }
    }

    @Test
    fun theSamePersonSignedInInTheAppIsMergedIntoTheCliAccount() = withTemporaryDirectory { directory ->
        runTest {
            val f = Fixture(directory)
            f.accounts.save(Account("a1", "https://api.example", "ada", name = "Ada Lovelace", organizationId = "o2", organizationName = "Globex"))
            f.credentials.write("a1", "osm-app")
            f.credentials.write(unlockKeyEntry("a1"), "unlock")
            f.accounts.save(Account("a2", "https://api.example", "grace"))
            f.accounts.setActive("a1")
            f.cli.login("https://api.example", "osm-1")
            f.sync.sync()
            assertEquals(listOf("a2", Account.CLI_ACCOUNT_ID), f.accounts.accounts.value.map { it.id }.sorted())
            val account = f.cliAccount()!!
            assertEquals("Ada Lovelace", account.name)
            assertEquals("o2", account.organizationId, "The organization chosen in the app is kept")
            assertNull(account.memberId, "The membership read was another organization's")
            assertEquals(Account.CLI_ACCOUNT_ID, f.accounts.activeAccountId.value, "The merged account was the active one")
            assertNull(f.credentials.read("a1"))
            assertNull(f.credentials.read(unlockKeyEntry("a1")))
            assertTrue("abandon https://api.example osm-app" in f.auth.calls, "The app's own session is revoked")
        }
    }

    @Test
    fun anotherPersonOnTheSameServerIsNotMerged() = withTemporaryDirectory { directory ->
        runTest {
            val f = Fixture(directory)
            f.accounts.save(Account("a1", "https://api.example", "grace"))
            f.cli.login("https://api.example", "osm-1")
            f.sync.sync()
            assertEquals(2, f.accounts.accounts.value.size)
            assertEquals("a1", f.accounts.activeAccountId.value)
        }
    }

    @Test
    fun aCancelledDescriptionPropagates() = withTemporaryDirectory { directory ->
        runTest {
            val f = Fixture(directory)
            f.auth.failure = CancellationException("closed")
            f.cli.login("https://api.example", "osm-1")
            assertFailsWith<CancellationException> { f.sync.sync() }
        }
    }
}
