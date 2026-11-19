// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure

import kotlinx.coroutines.test.runTest
import run.ostrom.app.CAPABILITIES
import run.ostrom.app.FakeAuthRepository
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.ServerCapabilities
import run.ostrom.app.domain.repositories.AuthRepository
import run.ostrom.app.infrastructure.repositories.CachedFeaturesRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Capabilities read once per account and server, read again on refresh, never served for another server. */
class CachedFeaturesRepositoryTest {
    private class CountingAuth(private val inner: FakeAuthRepository, var answer: ServerCapabilities) : AuthRepository by inner {
        var reads = 0
        override suspend fun capabilities(accountId: String): ServerCapabilities = answer.also { reads++ }
    }

    private val accounts = accounts().apply { save(account("a1")) }
    private val auth = CountingAuth(FakeAuthRepository(), CAPABILITIES)
    private val features = CachedFeaturesRepository(auth, accounts)

    @Test fun capabilitiesAreReadOnceThenServedFromTheCache() = runTest {
        assertNull(features.cached("a1"), "nothing before the first read")
        assertEquals(CAPABILITIES, features.capabilities("a1"))
        assertEquals(CAPABILITIES, features.capabilities("a1"))
        assertEquals(1, auth.reads)
        assertEquals(CAPABILITIES, features.cached("a1"))
    }

    @Test fun aRefreshReadsAgainAndReplacesTheCachedAnswer() = runTest {
        features.capabilities("a1")
        val newer = CAPABILITIES.copy(serverVersion = "1.5.0")
        auth.answer = newer
        assertEquals(newer, features.capabilities("a1", refresh = true))
        assertEquals(2, auth.reads)
        assertEquals(newer, features.capabilities("a1"))
        assertEquals(2, auth.reads)
    }

    @Test fun anAccountThatMovedToAnotherServerIsNotServedTheOldServersAnswer() = runTest {
        features.capabilities("a1")
        accounts.update(account("a1", origin = "https://other.example.org"))
        assertNull(features.cached("a1"))
        auth.answer = CAPABILITIES.copy(serverVersion = "9.9.9")
        assertEquals("9.9.9", features.capabilities("a1").serverVersion)
        assertEquals(2, auth.reads)
    }

    @Test fun cachesAreKeptPerAccount() = runTest {
        accounts.save(account("a2", login = "bob"))
        features.capabilities("a1")
        assertNull(features.cached("a2"))
        features.capabilities("a2")
        assertEquals(2, auth.reads)
    }

    @Test fun anUnknownAccountIsSignedOutForAReadAndHasNothingCached() = runTest {
        assertFailsWith<AppException.Unauthorized> { features.capabilities("ghost") }
        assertNull(features.cached("ghost"))
        assertEquals(0, auth.reads)
    }
}
