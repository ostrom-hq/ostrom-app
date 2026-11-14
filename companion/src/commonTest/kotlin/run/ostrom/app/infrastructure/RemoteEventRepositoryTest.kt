// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion RemoteSessionRepositoryTest (Apache-2.0); see NOTICE.
package run.ostrom.app.infrastructure

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.OrgChange
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.infrastructure.client.OstromClientProvider
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.repositories.AccountCalls
import run.ostrom.app.infrastructure.repositories.RemoteEventRepository
import run.ostrom.app.infrastructure.stream.ReconnectPolicy
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteEventRepositoryTest {
    private val requests = mutableListOf<HttpRequestData>()
    /** One answer per connection, in order: an event stream, or a JSON refusal. */
    private val answers = ArrayDeque<Pair<HttpStatusCode, String>>()
    private val accounts = accounts()
    private val credentials = MemoryCredentialStore()

    private val engine = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = MockEngine(MockEngineConfig().apply {
            block()
            addHandler { request ->
                requests += request
                val (status, body) = answers.removeFirst()
                val type = if (status == HttpStatusCode.OK) "text/event-stream" else "application/json"
                respond(body, status, headersOf(HttpHeaders.ContentType, type))
            }
        })
    }
    private val platform = object : PlatformInfo {
        override val platform = "Test"
        override val deviceModel = "Test"
        override val appVersion = "1.0.0"
    }
    private val repository = RemoteEventRepository(
        AccountCalls(OstromClientProvider(engine, credentials, accounts, platform), accounts),
        ReconnectPolicy(firstDelayMillis = 10, random = Random(0)),
    )

    private suspend fun signedIn() {
        accounts.save(account())
        credentials.write("a1", "osm_token")
    }

    private fun event(seq: Long, type: String, subjectId: String, memberId: String?) =
        "id: $seq\nevent: $type\ndata: {\"seq\":$seq,\"type\":\"$type\",\"subject\":\"wait\",\"subjectId\":\"$subjectId\"," +
            (if (memberId != null) "\"memberId\":\"$memberId\"," else "") +
            "\"at\":\"2026-11-14T10:00:00Z\",\"data\":{\"waitId\":\"$subjectId\"},\"newer\":true}\n\n"

    @Test
    fun theFeedOpensReconnectsAfterTheLastEventAndEndsWhenTheServerStopsAcceptingTheToken() = runTest {
        signedIn()
        answers += HttpStatusCode.OK to event(4, OrgChange.WAIT_CREATED, "w1", "m1") + event(5, OrgChange.WAIT_RESOLVED, "w2", null)
        answers += HttpStatusCode.OK to "event: gap\ndata: {\"oldestSeq\":9}\n\n" + event(9, OrgChange.WAIT_CANCELLED, "w3", "m1") +
            "event: denied\ndata: {}\n\n"
        val updates = mutableListOf<LiveUpdate<OrgChange>>()
        assertFailsWith<AppException.Unauthorized> {
            repository.changes("a1", OrgChange.WAIT_TYPES).collect { updates += it }
        }
        assertEquals(LiveUpdate.Opened(resumed = false), updates[0])
        assertEquals(LiveUpdate.Item(OrgChange(OrgChange.WAIT_CREATED, "w1", "m1")), updates[1])
        assertEquals(LiveUpdate.Item(OrgChange(OrgChange.WAIT_RESOLVED, "w2", null)), updates[2])
        assertTrue(updates[3] is LiveUpdate.Reconnecting, "A closed stream reconnects: ${updates[3]}")
        assertEquals(
            listOf(LiveUpdate.Opened(resumed = true), LiveUpdate.Gap, LiveUpdate.Item(OrgChange(OrgChange.WAIT_CANCELLED, "w3", "m1"))),
            updates.drop(4),
        )
        assertEquals(2, requests.size)
        assertNull(requests[0].headers["Last-Event-ID"], "The first connection starts from now")
        assertEquals("5", requests[1].headers["Last-Event-ID"], "The next one resumes after the last event")
        assertEquals("cloud.wait.cancelled,cloud.wait.created,cloud.wait.resolved", requests[0].url.parameters["types"])
        assertEquals("/api/v1/events", requests[0].url.encodedPath)
    }

    @Test
    fun aRefusalOrAnUnreadableEventEndsTheFeedWithoutRetrying() = runTest {
        signedIn()
        answers += HttpStatusCode.Forbidden to """{"error":{"type":"forbidden","message":"m"}}"""
        val refused = assertFailsWith<AppException.Server> { repository.changes("a1", OrgChange.WAIT_TYPES).collect { } }
        assertEquals(403, refused.statusCode)
        answers += HttpStatusCode.OK to "id: 1\nevent: cloud.wait.created\ndata: {\"seq\":\"one\"}\n\n"
        val updates = mutableListOf<LiveUpdate<OrgChange>>()
        val unreadable = assertFailsWith<AppException.Server> { repository.changes("a1", OrgChange.WAIT_TYPES).collect { updates += it } }
        assertEquals("malformed_event", unreadable.code)
        assertEquals(listOf<LiveUpdate<OrgChange>>(LiveUpdate.Opened(resumed = false)), updates)
        assertEquals(2, requests.size, "Neither is tried again")
    }

    @Test
    fun aSignedOutAccountHasNoFeed() = runTest {
        assertFailsWith<AppException.Unauthorized> { repository.changes("a1", OrgChange.WAIT_TYPES).collect { } }
        assertEquals(emptyList(), requests)
    }
}
