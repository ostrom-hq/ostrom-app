// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure

import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import run.ostrom.app.ORIGIN
import run.ostrom.app.account
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.services.CredentialStore
import run.ostrom.app.infrastructure.client.OstromClientProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** One client per account: reused for its server, replaced when the server changes, and acting with what is stored now. */
class OstromClientProviderTest {
    private val server = ScriptedServer()
    private val empty = """{"items":[]}"""

    @Test fun anAccountsClientIsReusedForItsServerAndSendsTheStoredTokenAndOrganization() = runTest {
        server.signedIn()
        server.accounts.update(account(origin = ORIGIN).copy(organizationId = "org-1"))
        val first = server.provider.forAccount("a1", ORIGIN)
        assertSame(first, server.provider.forAccount("a1", ORIGIN))
        server.json(empty)
        first.organizations.mine()
        val request = server.requests.single()
        assertEquals("Bearer osm_token", request.headers[HttpHeaders.Authorization])
        assertEquals("org-1", request.headers["Ostrom-Org"])
        assertEquals("Ostrom-App/1.0.0 (Test)", request.headers[HttpHeaders.UserAgent])
    }

    @Test fun aTokenWrittenAfterTheClientWasCreatedIsUsedAtOnce() = runTest {
        server.signedIn()
        val client = server.provider.forAccount("a1", ORIGIN)
        server.credentials.write("a1", "osm_rotated")
        server.json(empty)
        client.organizations.mine()
        assertEquals("Bearer osm_rotated", server.requests.single().headers[HttpHeaders.Authorization])
    }

    @Test fun aNewServerForTheSameAccountReplacesAndClosesTheOldClient() = runTest {
        server.signedIn()
        val old = server.provider.forAccount("a1", ORIGIN)
        val moved = server.provider.forAccount("a1", "https://other.example.org")
        assertTrue(old !== moved)
        assertFailsWith<Exception> { old.organizations.mine() }
        server.json(empty)
        assertTrue(moved.organizations.mine().isEmpty())
        assertEquals("other.example.org", server.requests.single().url.host)
    }

    @Test fun releasingAnAccountClosesItsClientAndTheNextCallBuildsAnother() = runTest {
        server.signedIn()
        val client = server.provider.forAccount("a1", ORIGIN)
        server.provider.release("a1")
        assertFailsWith<Exception> { client.organizations.mine() }
        assertTrue(server.provider.forAccount("a1", ORIGIN) !== client)
    }

    @Test fun releasingAnUnknownAccountDoesNothing() = runTest {
        server.provider.release("ghost")
    }

    @Test fun aDetachedClientSendsTheGivenTokenOrNone() = runTest {
        server.json(empty)
        server.json(empty)
        server.provider.detached(ORIGIN, "osm_given").use { it.organizations.mine() }
        server.provider.detached(ORIGIN).use { it.organizations.mine() }
        assertEquals("Bearer osm_given", server.requests[0].headers[HttpHeaders.Authorization])
        assertNull(server.requests[1].headers[HttpHeaders.Authorization])
        assertNull(server.requests[1].headers["Ostrom-Org"])
    }

    @Test fun aClientInAnOrganizationActsThereWhateverTheAccountChoseSince() = runTest {
        server.signedIn()
        server.accounts.update(account().copy(organizationId = "org-now"))
        server.json(empty)
        server.provider.inOrganization("a1", ORIGIN, "org-then").use { it.organizations.mine() }
        val request = server.requests.single()
        assertEquals("org-then", request.headers["Ostrom-Org"])
        assertEquals("Bearer osm_token", request.headers[HttpHeaders.Authorization])
    }

    @Test fun aSecureStorageFailureIsReportedAsSuchNotAsAnUnreachableServer() = runTest {
        val broken = object : CredentialStore {
            override val persistent = true
            override suspend fun read(accountId: String): String? = throw IllegalStateException("keystore locked")
            override suspend fun write(accountId: String, token: String) = Unit
            override suspend fun delete(accountId: String) = Unit
        }
        val provider = OstromClientProvider(server.engine, broken, server.accounts, server.platform)
        assertFailsWith<AppException.CredentialStorage> { provider.inOrganization("a1", ORIGIN, null).use { it.organizations.mine() } }
    }

    @Test fun aCancellationWhileReadingTheTokenIsNotReportedAsAStorageFailure() = runTest {
        val cancelled = object : CredentialStore {
            override val persistent = true
            override suspend fun read(accountId: String): String? = throw CancellationException("stop")
            override suspend fun write(accountId: String, token: String) = Unit
            override suspend fun delete(accountId: String) = Unit
        }
        val provider = OstromClientProvider(server.engine, cancelled, server.accounts, server.platform)
        assertFailsWith<CancellationException> { provider.inOrganization("a1", ORIGIN, null).use { it.organizations.mine() } }
    }
}
