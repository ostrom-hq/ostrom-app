// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import run.ostrom.app.CountingRandom
import run.ostrom.app.ORIGIN
import run.ostrom.app.SERVER
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Organization
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.repositories.DevicePoll
import run.ostrom.app.domain.repositories.Member
import run.ostrom.app.infrastructure.client.OstromClientProvider
import run.ostrom.app.infrastructure.repositories.AccountCalls
import run.ostrom.app.infrastructure.repositories.RemoteAuthRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the sign-in refuses: a server that lies about its page or codes, odd answers, and what is read about the member. */
class RemoteAuthRepositoryFailuresTest {
    private val server = ScriptedServer()
    private val repository = RemoteAuthRepository(server.provider, server.calls, CountingRandom, CoroutineScope(Dispatchers.Unconfined))

    private fun start(deviceCode: String = "dc", userCode: String = "ABCD-EFGH", page: String = "https://github.com/login/device") =
        """{"deviceCode":"$deviceCode","userCode":"$userCode","verificationUri":"$page","verificationUriComplete":"$page","interval":5,"expiresIn":900}"""

    private fun error(status: HttpStatusCode, type: String) = server.json("""{"error":{"type":"$type","message":"m"}}""", status)

    @Test fun aPageWithAControlCharacterIsNeverOpenedEvenOnGitHub() = runTest {
        server.json(start(page = "https://github.com/login/dev\\u0007ice"))
        assertFailsWith<AppException.UntrustedSignInPage> { repository.startDeviceLogin(SERVER) }
        server.json(start(page = "https://github.com/login/dev\\u007fice"))
        assertFailsWith<AppException.UntrustedSignInPage> { repository.startDeviceLogin(SERVER) }
        server.json(start())
        assertEquals("https://github.com/login/device", repository.startDeviceLogin(SERVER).verificationUri)
    }

    @Test fun aDeviceCodeThatIsEmptyOrAUserCodeThatIsNotACodeIsNotOstrom() = runTest {
        server.json(start(deviceCode = ""))
        assertFailsWith<AppException.NotOstrom> { repository.startDeviceLogin(SERVER) }
        server.json(start(userCode = "AB CD"))
        assertFailsWith<AppException.NotOstrom> { repository.startDeviceLogin(SERVER) }
        server.json(start(userCode = ""))
        assertFailsWith<AppException.NotOstrom> { repository.startDeviceLogin(SERVER) }
    }

    @Test fun aPageOnTheServersOwnOriginOrItsConsoleIsTrustedAndAnUnreadableOneIsNot() {
        assertTrue(RemoteAuthRepository.trustedSignInPage("https://ostrom.example.org/device", "https://ostrom.example.org"))
        assertTrue(RemoteAuthRepository.trustedSignInPage("HTTPS://OSTROM.example.org:443/device", "https://ostrom.example.org"), "case and default port")
        assertEquals(false, RemoteAuthRepository.trustedSignInPage("not a url", "https://ostrom.example.org"))
        assertEquals(false, RemoteAuthRepository.trustedSignInPage("https://ostrom.example.org:8443/device", "https://ostrom.example.org"), "another port")
        assertEquals(false, RemoteAuthRepository.trustedSignInPage("https://ostrom.example.org/device", "garbage"), "a server origin that is not one")
        assertTrue(RemoteAuthRepository.trustedSignInPage("http://localhost:8080/x", "http://localhost:8080"))
        assertEquals(false, RemoteAuthRepository.trustedSignInPage("http://example.org/x", "http://example.org"), "plain http only to this machine")
    }

    @Test fun anInstantThatIsNotRfc3339ReadsAsNone() {
        assertEquals(1_797_156_000_000L, RemoteAuthRepository.epochMillis("2026-12-13T10:00:00Z"))
        assertNull(RemoteAuthRepository.epochMillis("tomorrow"))
    }

    @Test fun aPollAnswerWithoutATokenOrLoginIsNotOstrom() = runTest {
        server.json("""{"token":"","login":"ada","scopes":[]}""")
        assertFailsWith<AppException.NotOstrom> { repository.pollDeviceLogin(ORIGIN, "dc") }
        server.json("""{"token":"osm_x","login":"","scopes":[]}""")
        assertFailsWith<AppException.NotOstrom> { repository.pollDeviceLogin(ORIGIN, "dc") }
    }

    @Test fun aPollAnswerWithAnExpiryKeepsItInMillis() = runTest {
        server.json("""{"token":"osm_x","login":"ada","scopes":[],"expiresAt":"2026-12-13T10:00:00Z"}""")
        val done = assertIs<DevicePoll.Done>(repository.pollDeviceLogin(ORIGIN, "dc"))
        assertEquals(1_797_156_000_000L, done.session.expiresAt)
        assertNull(done.session.name)
    }

    @Test fun pendingIsRecognizedByItsStatusAloneOrByItsKeyAlone() = runTest {
        error(HttpStatusCode(428, "Precondition Required"), "something_else")
        assertEquals(DevicePoll.Pending(), repository.pollDeviceLogin(ORIGIN, "dc"))
        error(HttpStatusCode.BadRequest, "authorization_pending")
        assertEquals(DevicePoll.Pending(), repository.pollDeviceLogin(ORIGIN, "dc"))
    }

    @Test fun anyOtherPollRefusalIsReportedAsTheServerFailureItIs() = runTest {
        error(HttpStatusCode.BadRequest, "invalid_grant")
        val failure = assertFailsWith<AppException.Server> { repository.pollDeviceLogin(ORIGIN, "dc") }
        assertEquals("invalid_grant", failure.code)
    }

    @Test fun aServerWithoutCapabilitiesIsTooOldWhetherReadWithATokenOrForAnAccount() = runTest {
        server.signedIn()
        server.json("{}", HttpStatusCode.NotFound)
        assertFailsWith<AppException.UnsupportedApi> { repository.capabilities("a1") }
        server.json("{}", HttpStatusCode.InternalServerError)
        assertEquals(500, assertFailsWith<AppException.Server> { repository.capabilities("a1") }.statusCode)
        assertFailsWith<AppException.Unauthorized> { repository.capabilities("ghost") }
    }

    @Test fun aNotFoundRaisedWhileResolvingTheAccountIsAlsoATooOldServer() = runTest {
        val broken = object : AccountRepository by server.accounts {
            override val accounts: StateFlow<List<Account>> get() = throw AppException.NotFound()
        }
        val calls = AccountCalls(OstromClientProvider(server.engine, server.credentials, broken, server.platform), broken)
        val repository = RemoteAuthRepository(server.provider, calls, CountingRandom, CoroutineScope(Dispatchers.Unconfined))
        assertFailsWith<AppException.UnsupportedApi> { repository.capabilities("a1") }
    }

    @Test fun aMemberIsReadWithItsOrganizationAndMembershipWhenThereAreOnes() = runTest {
        server.json("""{"dev":{"login":"ada","verified":true,"via":"device"},"capabilities":[]}""")
        assertEquals(Member("ada", null, null), repository.me(ORIGIN, "t"))
        server.json(
            """{"dev":{"login":"ada","verified":true,"via":"device"},"capabilities":[],
               "member":{"id":"m1","login":"ada","name":"Ada","avatarUrl":"","role":"owner"},
               "organization":{"id":"o1","name":"Analytical Engines","avatarUrl":""}}""",
        )
        assertEquals(Member("ada", Organization("o1", "Analytical Engines"), "m1"), repository.me(ORIGIN, "t"))
    }

    @Test fun anAccountsMemberIsReadWithItsToken() = runTest {
        server.signedIn()
        server.json("""{"dev":{"login":"grace","verified":true,"via":"device"},"capabilities":[]}""")
        assertEquals("grace", repository.me("a1").login)
        assertEquals("Bearer osm_token", server.requests.single().headers["Authorization"])
    }

    @Test fun anOrganizationWithARoleThisVersionDoesNotKnowHasNoRole() = runTest {
        server.json(
            """{"items":[{"id":"o1","slug":"a","name":"A","avatarUrl":"","role":"future-role"},
                        {"id":"o2","slug":"b","name":"B","avatarUrl":"","role":"admin"}]}""",
        )
        assertEquals(listOf(Organization("o1", "A", null), Organization("o2", "B", "admin")), repository.organizations(ORIGIN, "t"))
    }

    @Test fun signingOutCallsTheServerAndReleasingDropsTheClient() = runTest {
        server.signedIn()
        server.json("{}", HttpStatusCode.NoContent)
        repository.logout("a1")
        assertEquals("/api/v1/auth/logout", server.requests.single().url.encodedPath)
        repository.release("a1")
    }

    @Test fun abandoningASessionBestEffortCallsLogoutAndNeverFailsWhenTheServerIsDown() = runTest {
        server.json("{}", HttpStatusCode.NoContent)
        repository.abandon(ORIGIN, "osm_old")
        withContext(Dispatchers.Default) { withTimeout(10_000) { while (server.requests.size < 1) delay(10) } }
        assertEquals("Bearer osm_old", server.requests.single().headers["Authorization"])
        server.json("{}", HttpStatusCode.InternalServerError)
        repository.abandon(ORIGIN, "osm_old2")
        withContext(Dispatchers.Default) { withTimeout(10_000) { while (server.requests.size < 2) delay(10) } }
        assertEquals("Bearer osm_old2", server.requests.last().headers["Authorization"])
    }
}
