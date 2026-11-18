// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.domain.models.CloudSessionStatus
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.ServerCapabilities
import run.ostrom.app.domain.repositories.FeaturesRepository
import run.ostrom.app.presentation.navigation.TransferRoute
import run.ostrom.app.presentation.navigation.opensSessions
import run.ostrom.app.presentation.navigation.servesSessions
import run.ostrom.app.presentation.navigation.transferRouteOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionsAccessTest {
    private class StubFeatures(var cached: ServerCapabilities?, var failure: Exception? = null, var features: Set<String> = emptySet()) : FeaturesRepository {
        override suspend fun capabilities(accountId: String, refresh: Boolean): ServerCapabilities {
            failure?.let { throw it }
            return ServerCapabilities("1.4.0", 1, features)
        }
        override fun cached(accountId: String): ServerCapabilities? = cached
    }

    private fun caps(vararg features: String) = ServerCapabilities("1.4.0", 1, features.toSet())

    @Test
    fun sessionsOpenOnlyWhenTheCachedCapabilitiesAnnounceThem() {
        assertFalse(StubFeatures(null).opensSessions("a1"), "nothing cached yet")
        assertFalse(StubFeatures(caps(Features.GATES)).opensSessions("a1"))
        assertTrue(StubFeatures(caps(Features.SESSIONS)).opensSessions("a1"))
    }

    @Test
    fun theServerIsAskedAndOfflineWhatWasCachedDecides() = runTest {
        assertTrue(StubFeatures(null, features = setOf(Features.SESSIONS)).servesSessions("a1"), "read again when nothing was cached")
        assertFalse(StubFeatures(caps(Features.SESSIONS), features = setOf(Features.GATES)).servesSessions("a1"), "the server's answer wins")
        assertTrue(StubFeatures(caps(Features.SESSIONS), failure = IllegalStateException("offline")).servesSessions("a1"))
        assertFalse(StubFeatures(null, failure = IllegalStateException("offline")).servesSessions("a1"))
        assertFailsWith<CancellationException> { StubFeatures(caps(Features.SESSIONS), failure = CancellationException("left")).servesSessions("a1") }
    }

    private fun session(ownerId: String?) = CloudSession(
        "s1", "Mill", "claude", null, null, null, CloudSessionStatus.RUNNING, null, null, null, null, 1L, 1L, null, null, null, null, null, ownerId = ownerId,
    )

    @Test
    fun aSessionIsGivenFromItsOwnerOnly() {
        assertEquals(TransferRoute("a1", "s1", "u9"), transferRouteOf("a1", session("u9")))
        assertNull(transferRouteOf("a1", session(null)))
    }
}
