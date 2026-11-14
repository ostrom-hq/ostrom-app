// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../RemoteNotificationPreferencesRepositoryTest.kt; see NOTICE.
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
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.NotificationKind
import run.ostrom.app.domain.models.NotificationSwitch
import run.ostrom.app.domain.models.PushTestOutcome
import run.ostrom.app.domain.models.PushTestPhone
import run.ostrom.app.domain.models.PushTestStatus
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.infrastructure.client.OstromClientProvider
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.repositories.AccountCalls
import run.ostrom.app.infrastructure.repositories.RemoteNotificationSettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RemoteNotificationSettingsRepositoryTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val accounts = accounts()
    private val credentials = MemoryCredentialStore()
    private var status = HttpStatusCode.OK
    private var headers = headersOf(HttpHeaders.ContentType, "application/json")
    private var answer = """{"channels":["push"],"settings":[{"channel":"push","kind":"waiting","enabled":false},""" +
        """{"channel":"push","kind":"turn_completed","enabled":true},{"channel":"push","kind":"deployed","enabled":false}]}"""

    private val engine = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = MockEngine(MockEngineConfig().apply {
            block()
            addHandler { request ->
                requests += request
                respond(answer, status, headers)
            }
        })
    }
    private val platform = object : PlatformInfo {
        override val platform = "Test"
        override val deviceModel = "Test"
        override val appVersion = "1.0.0"
    }
    private val repository = RemoteNotificationSettingsRepository(AccountCalls(OstromClientProvider(engine, credentials, accounts, platform), accounts))

    private suspend fun signedIn() {
        accounts.save(account())
        credentials.write("a1", "osm_token")
    }

    private fun refusal(code: Int, type: String, extra: List<Pair<String, String>> = emptyList()) {
        status = HttpStatusCode.fromValue(code)
        answer = """{"error":{"type":"$type","message":"m"}}"""
        headers = headersOf(*(listOf(HttpHeaders.ContentType to "application/json") + extra).map { it.first to listOf(it.second) }.toTypedArray())
    }

    @Test
    fun theSwitchesAreReadAndAKindThisAppDoesNotKnowIsLeftOut() = runTest {
        signedIn()
        val read = repository.settings("a1")
        assertEquals(HttpMethod.Get, requests.single().method)
        assertEquals("/api/v1/notifications/settings", requests.single().url.encodedPath)
        assertEquals(listOf("push"), read.channels)
        assertEquals(
            mapOf(NotificationSwitch("push", NotificationKind.WAITING) to false, NotificationSwitch("push", NotificationKind.TURN_COMPLETED) to true),
            read.switches,
        )
    }

    @Test
    fun oneSwitchIsSentAndAllAreReadBack() = runTest {
        signedIn()
        val saved = repository.set("a1", "push", NotificationKind.TURN_FAILED, false)
        val request = requests.single()
        assertEquals(HttpMethod.Put, request.method)
        assertEquals("/api/v1/notifications/settings", request.url.encodedPath)
        val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertEquals("""{"channel":"push","kind":"turn_failed","enabled":false}""", body)
        assertEquals(false, saved.isOn("push", NotificationKind.WAITING))
    }

    @Test
    fun aTestAnswersEachPhone() = runTest {
        signedIn()
        answer = """{"ok":true,"results":[{"status":"sent"},{"status":"rejected","error":"bad_token"},{"status":"queued"}]}"""
        val outcome = repository.test("a1")
        assertEquals(HttpMethod.Post, requests.single().method)
        assertEquals("/api/v1/notifications/push/test", requests.single().url.encodedPath)
        assertEquals(
            PushTestOutcome.Sent(listOf(PushTestPhone(PushTestStatus.SENT), PushTestPhone(PushTestStatus.REJECTED, "bad_token"), PushTestPhone(PushTestStatus.OTHER))),
            outcome,
        )
    }

    @Test
    fun pushOffAndNoPhoneAreOutcomesNotFailures() = runTest {
        signedIn()
        refusal(409, "push_off")
        assertEquals(PushTestOutcome.Off, repository.test("a1"))
        refusal(409, "push_no_device")
        assertEquals(PushTestOutcome.NoPhone, repository.test("a1"))
    }

    @Test
    fun anotherRefusalReachesTheScreenAsAFailure() = runTest {
        signedIn()
        refusal(429, "push_test_rate_limited", listOf(HttpHeaders.RetryAfter to "10"))
        assertEquals(10L, assertFailsWith<AppException.RateLimited> { repository.test("a1") }.retryAfterSeconds)
        refusal(409, "org_required")
        assertFailsWith<AppException.OrganizationRequired> { repository.test("a1") }
        refusal(403, "forbidden")
        assertEquals(403, assertFailsWith<AppException.Server> { repository.test("a1") }.statusCode)
    }
}
