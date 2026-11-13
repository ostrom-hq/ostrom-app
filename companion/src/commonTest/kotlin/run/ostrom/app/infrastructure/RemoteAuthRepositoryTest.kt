// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.app.infrastructure

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import run.ostrom.app.CountingRandom
import run.ostrom.app.ORIGIN
import run.ostrom.app.SERVER
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.repositories.DevicePoll
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.infrastructure.client.OstromClientProvider
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.repositories.AccountCalls
import run.ostrom.app.infrastructure.repositories.RemoteAuthRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteAuthRepositoryTest {
    private val requests = mutableListOf<HttpRequestData>()
    private var answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { respond("", HttpStatusCode.NotFound) }
    private val accounts = accounts()
    private val credentials = MemoryCredentialStore()

    private val engine = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = MockEngine(MockEngineConfig().apply {
            block()
            addHandler { request ->
                requests += request
                answer(request)
            }
        })
    }
    private val platform = object : PlatformInfo {
        override val platform = "Test"
        override val deviceModel = "Test"
        override val appVersion = "1.0.0"
    }
    private val clients = OstromClientProvider(engine, credentials, accounts, platform)
    private val repository = RemoteAuthRepository(clients, AccountCalls(clients, accounts), CountingRandom, CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))

    private fun json(body: String, status: HttpStatusCode = HttpStatusCode.OK, retryAfter: String? = null): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        val headers = if (retryAfter != null) headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOf(retryAfter))
        else headersOf(HttpHeaders.ContentType, "application/json")
        respond(body, status, headers)
    }

    private fun error(status: HttpStatusCode, type: String) = json("""{"error":{"type":"$type","message":"m"}}""", status)

    @Test
    fun discoveryReadsTheSignInMethods() = runTest {
        answer = json("""{"methods":["github","email"],"signUp":"invite"}""")
        val server = repository.discover(ORIGIN)
        assertEquals(setOf("github", "email"), server.methods)
        assertTrue(server.inviteOnly)
        assertEquals("/api/v1/auth/providers", requests.single().url.encodedPath)
        assertTrue(requests.single().headers[HttpHeaders.UserAgent]!!.startsWith("Ostrom-App/1.0.0"))
    }

    @Test
    fun aPageThatIsNotOstromIsSaidSo() = runTest {
        answer = { respond("<html>portal</html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html")) }
        assertFailsWith<AppException.NotOstrom> { repository.discover(ORIGIN) }
        answer = json("""{"unexpected":true}""")
        assertFailsWith<AppException.NotOstrom> { repository.discover(ORIGIN) }
    }

    @Test
    fun failuresAreTranslated() = runTest {
        answer = json("""{"error":{"type":"rate_limited","message":"m"}}""", HttpStatusCode.TooManyRequests, retryAfter = "12")
        assertEquals(12L, assertFailsWith<AppException.RateLimited> { repository.discover(ORIGIN) }.retryAfterSeconds)
        answer = error(HttpStatusCode.InternalServerError, "internal")
        val server = assertFailsWith<AppException.Server> { repository.discover(ORIGIN) }
        assertEquals(500, server.statusCode)
        answer = error(HttpStatusCode.Unauthorized, "unauthorized")
        assertFailsWith<AppException.Unauthorized> { repository.me(ORIGIN, "t") }
        answer = error(HttpStatusCode.Conflict, "org_required")
        assertFailsWith<AppException.OrganizationRequired> { repository.organizations(ORIGIN, "t") }
        assertFailsWith<AppException.InvalidOrigin> { repository.discover("http://ostrom.example.org") }
    }

    @Test
    fun aServerWithoutCapabilitiesIsTooOld() = runTest {
        answer = { respond("", HttpStatusCode.NotFound) }
        assertNull(assertFailsWith<AppException.UnsupportedApi> { repository.capabilities(ORIGIN, "t") }.apiVersion)
        answer = json("""{"apiVersion":1,"serverVersion":"1.4.0","features":["gates.v1"]}""")
        val capabilities = repository.capabilities(ORIGIN, "osm_t")
        assertEquals(setOf("gates.v1"), capabilities.features)
        assertEquals("Bearer osm_t", requests.last().headers[HttpHeaders.Authorization])
    }

    @Test
    fun anAccountsCallsCarryItsTokenAndOrganization() = runTest {
        accounts.save(account().copy(organizationId = "org-1"))
        credentials.write("a1", "osm_stored")
        answer = json("""{"items":[{"id":"org-1","slug":"ae","name":"Analytical Engines","avatarUrl":"","role":"owner"}]}""")
        assertEquals("Analytical Engines", repository.organizations("a1").single().name)
        assertEquals("Bearer osm_stored", requests.last().headers[HttpHeaders.Authorization])
        assertEquals("org-1", requests.last().headers["Ostrom-Org"])
        assertFailsWith<AppException.Unauthorized> { repository.organizations("unknown") }
    }

    @Test
    fun theBrowserSignInStartsWithPkce() {
        val attempt = repository.prepareBrowserSignIn(ORIGIN, "ostrom://auth/callback", "st4te")
        assertTrue(attempt.url.startsWith("$ORIGIN/api/v1/auth/github/start?"), attempt.url)
        assertTrue("code_challenge_method=S256" in attempt.url)
        assertTrue("state=st4te" in attempt.url)
        assertEquals(43, attempt.codeVerifier.length)
    }

    @Test
    fun theMobileCodeIsExchangedForAnAppTokenAndItsUnlockKey() = runTest {
        answer = json("""{"token":"osm_app","user":{"login":"ada","name":"Ada"},"expiresAt":"2026-12-13T10:00:00Z","unlockKey":"uk"}""")
        val session = repository.exchangeCode(ORIGIN, "code", "verifier", "ostrom://auth/callback")
        assertEquals("osm_app", session.token)
        assertEquals("Ada", session.name)
        assertEquals("uk", session.unlockKey)
        assertEquals(1_797_156_000_000L, session.expiresAt)
    }

    @Test
    fun theDeviceFlowOnlyOpensATrustedPage() = runTest {
        answer = json("""{"deviceCode":"dc","userCode":"ABCD-EFGH","verificationUri":"https://github.com/login/device","interval":0,"expiresIn":99999}""")
        val login = repository.startDeviceLogin(SERVER)
        assertEquals("ABCD-EFGH", login.userCode)
        assertEquals(1_000L, login.intervalMillis)
        assertEquals(1_800_000L, login.expiresInMillis)
        answer = json("""{"deviceCode":"dc","userCode":"ABCD","verificationUri":"https://app.ostrom.example.org/device","interval":5,"expiresIn":900}""")
        assertFailsWith<AppException.UntrustedSignInPage> { repository.startDeviceLogin(SERVER) }
        answer = json("""{"deviceCode":"dc","userCode":"ABCD","verificationUri":"https://evil.example.org/device","interval":5,"expiresIn":900}""")
        assertFailsWith<AppException.UntrustedSignInPage> { repository.startDeviceLogin(SERVER) }
        assertTrue(RemoteAuthRepository.trustedSignInPage("https://app.example.org/device", "https://api.example.org"))
        assertTrue(RemoteAuthRepository.trustedSignInPage("http://localhost:8080/device", "http://localhost:8080"))
        assertFalse(RemoteAuthRepository.trustedSignInPage("http://ostrom.example.org/device", "https://ostrom.example.org"))
    }

    @Test
    fun theDeviceFlowPollingSaysWhereItIs() = runTest {
        answer = error(HttpStatusCode(428, "Precondition Required"), "authorization_pending")
        assertEquals(DevicePoll.Pending(), repository.pollDeviceLogin(ORIGIN, "dc"))
        answer = error(HttpStatusCode.BadRequest, "slow_down")
        assertEquals(DevicePoll.Pending(slowDown = true), repository.pollDeviceLogin(ORIGIN, "dc"))
        answer = error(HttpStatusCode.BadRequest, "expired_token")
        assertFailsWith<AppException.SignInExpired> { repository.pollDeviceLogin(ORIGIN, "dc") }
        answer = error(HttpStatusCode.BadRequest, "access_denied")
        assertFailsWith<AppException.SignInDenied> { repository.pollDeviceLogin(ORIGIN, "dc") }
        answer = json("""{"token":"osm_cli","login":"ada","scopes":[]}""")
        val done = assertIs<DevicePoll.Done>(repository.pollDeviceLogin(ORIGIN, "dc"))
        assertEquals("osm_cli", done.session.token)
    }
}
