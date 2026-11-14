// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../infrastructure/RemoteGitHubLinkRepositoryTest.kt; see NOTICE.
package run.ostrom.app.infrastructure

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.GitHubLinkState
import run.ostrom.app.domain.models.GitHubPoll
import run.ostrom.app.domain.models.GitHubRefusal
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.infrastructure.client.OstromClientProvider
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.repositories.AccountCalls
import run.ostrom.app.infrastructure.repositories.RemoteGitHubLinkRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The GitHub link over a scripted server: the routes, what is mapped, and how the refusals are worded by their key. */
class RemoteGitHubLinkRepositoryTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val answers = ArrayDeque<Pair<HttpStatusCode, String>>()
    private val accounts = accounts()
    private val credentials = MemoryCredentialStore()

    private val engine = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = MockEngine(MockEngineConfig().apply {
            block()
            addHandler { request ->
                requests += request
                val (status, body) = answers.removeFirstOrNull() ?: (HttpStatusCode.ServiceUnavailable to "{}")
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
    }
    private val platform = object : PlatformInfo {
        override val platform = "Test"
        override val deviceModel = "Test"
        override val appVersion = "1.0.0"
    }
    private val repository = RemoteGitHubLinkRepository(AccountCalls(OstromClientProvider(engine, credentials, accounts, platform), accounts))

    private suspend fun signedIn() {
        accounts.save(account())
        credentials.write("a1", "osm_token")
    }

    private fun answer(body: String, status: HttpStatusCode = HttpStatusCode.OK) = answers.addLast(status to body)

    private fun failure(type: String, status: HttpStatusCode) = answer("""{"error":{"type":"$type","message":"server text"}}""", status)

    private val linked = """{"memberId":"m1","state":"linked","appConfigured":true,"login":"octocat","userId":583231,"linkedAt":"2026-11-14T09:00:00Z"}"""
    private val device = """{"id":"d1","userCode":"ABCD-1234","verificationUri":"https://github.com/login/device","expiresAt":"2026-11-14T09:15:00Z","interval":5}"""

    @Test
    fun theLinkIsTheCallersOwnWithItsLogin() = runTest {
        signedIn()
        answer(linked)
        val link = repository.link("a1")
        assertEquals("/api/v1/github/link", requests.single().url.encodedPath)
        assertEquals(HttpMethod.Get, requests.single().method)
        assertEquals(GitHubLinkState.LINKED, link.state)
        assertEquals("octocat", link.login)
        assertEquals(1_794_646_800_000L, link.linkedAt)
        answer("""{"memberId":"m1","state":"reconnect_required","appConfigured":true,"login":"octocat"}""")
        assertEquals(GitHubLinkState.RECONNECT_REQUIRED, repository.link("a1").state)
        answer("""{"memberId":"m1","state":"not_linked","appConfigured":false}""")
        val none = repository.link("a1")
        assertEquals(GitHubLinkState.NOT_LINKED, none.state)
        assertFalse(none.appConfigured)
        assertNull(none.login)
        answer("""{"memberId":"m1","state":"suspended","appConfigured":true}""")
        assertEquals(GitHubLinkState.UNKNOWN, repository.link("a1").state)
    }

    @Test
    fun theDeviceFlowStartsAndPollsEachOutcome() = runTest {
        signedIn()
        answer(device)
        val started = repository.start("a1")
        assertEquals("/api/v1/github/link/device", requests.last().url.encodedPath)
        assertEquals(HttpMethod.Post, requests.last().method)
        assertEquals("ABCD-1234", started.userCode)
        assertEquals(5, started.interval)
        answer("""{"status":"pending","interval":10}""")
        assertEquals(GitHubPoll.Pending(10), repository.poll("a1", "d1"))
        assertEquals("/api/v1/github/link/device/d1", requests.last().url.encodedPath)
        assertEquals(HttpMethod.Post, requests.last().method)
        answer("""{"status":"linked","link":$linked}""")
        assertEquals("octocat", (repository.poll("a1", "d1") as GitHubPoll.Linked).link.login)
        answer("""{"status":"denied"}""")
        assertEquals(GitHubPoll.Denied, repository.poll("a1", "d1"))
        answer("""{"status":"expired"}""")
        assertEquals(GitHubPoll.Expired, repository.poll("a1", "d1"))
        answer("""{"status":"something_new"}""")
        assertEquals(GitHubPoll.Expired, repository.poll("a1", "d1"))
    }

    @Test
    fun aLinkedAnswerWithoutTheLinkReadsIt() = runTest {
        signedIn()
        answer("""{"status":"linked"}""")
        answer(linked)
        assertEquals("octocat", (repository.poll("a1", "d1") as GitHubPoll.Linked).link.login)
        assertEquals("/api/v1/github/link", requests.last().url.encodedPath)
    }

    @Test
    fun anAttemptTheServerNoLongerKnowsIsExpiredAndACancelledOneDenied() = runTest {
        signedIn()
        failure("github_link_not_found", HttpStatusCode.NotFound)
        assertEquals(GitHubPoll.Expired, repository.poll("a1", "d1"))
        failure("github_link_denied", HttpStatusCode.BadRequest)
        assertEquals(GitHubPoll.Denied, repository.poll("a1", "d1"))
    }

    @Test
    fun theRefusalsAreWordedByTheirKeyAndNeverByTheServersText() = runTest {
        signedIn()
        val keys = mapOf(
            "github_app_not_configured" to (HttpStatusCode.Conflict to GitHubRefusal.APP_NOT_CONFIGURED),
            "github_device_flow_disabled" to (HttpStatusCode.Conflict to GitHubRefusal.DEVICE_FLOW_DISABLED),
            "github_app_misconfigured" to (HttpStatusCode.Conflict to GitHubRefusal.APP_MISCONFIGURED),
            "github_account_in_use" to (HttpStatusCode.Conflict to GitHubRefusal.ACCOUNT_IN_USE),
            "github_unreachable" to (HttpStatusCode.ServiceUnavailable to GitHubRefusal.UNREACHABLE),
        )
        for ((key, expected) in keys) {
            failure(key, expected.first)
            val refused = assertFailsWith<AppException.GitHubRefused> { repository.start("a1") }
            assertEquals(expected.second, refused.reason)
            assertFalse("server text" in refused.message.orEmpty())
        }
        failure("github_unreachable", HttpStatusCode.ServiceUnavailable)
        assertEquals(GitHubRefusal.UNREACHABLE, assertFailsWith<AppException.GitHubRefused> { repository.poll("a1", "d1") }.reason)
        failure("something_else", HttpStatusCode.Conflict)
        assertEquals("something_else", assertFailsWith<AppException.Server> { repository.start("a1") }.code)
    }

    @Test
    fun unlinkingDeletesTheCallersLink() = runTest {
        signedIn()
        answer("", HttpStatusCode.NoContent)
        repository.unlink("a1")
        assertEquals(HttpMethod.Delete, requests.single().method)
        assertEquals("/api/v1/github/link", requests.single().url.encodedPath)
    }
}
