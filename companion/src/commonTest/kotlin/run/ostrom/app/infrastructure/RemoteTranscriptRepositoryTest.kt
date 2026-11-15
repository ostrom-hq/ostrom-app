// SPDX-License-Identifier: Apache-2.0
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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.KnownAgentState
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.TranscriptEvent
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.infrastructure.client.OstromClientProvider
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.repositories.AccountCalls
import run.ostrom.app.infrastructure.repositories.RemoteTranscriptRepository
import run.ostrom.app.infrastructure.stream.ReconnectPolicy
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** A conversation session's transcript over the typed client: its pages, its subagents and its live stream. */
class RemoteTranscriptRepositoryTest {
    private val requests = mutableListOf<HttpRequestData>()

    /** One answer per request, in order: a JSON body, an event stream, or a JSON refusal. */
    private val answers = ArrayDeque<Pair<HttpStatusCode, String>>()
    private val accounts = accounts()
    private val credentials = MemoryCredentialStore()

    private val engine = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = MockEngine(MockEngineConfig().apply {
            block()
            addHandler { request ->
                requests += request
                val (status, body) = answers.removeFirst()
                val stream = request.url.encodedPath.endsWith("/stream") && status == HttpStatusCode.OK
                respond(body, status, headersOf(HttpHeaders.ContentType, if (stream) "text/event-stream" else "application/json"))
            }
        })
    }
    private val platform = object : PlatformInfo {
        override val platform = "Test"
        override val deviceModel = "Test"
        override val appVersion = "1.0.0"
    }
    private val repository = RemoteTranscriptRepository(
        AccountCalls(OstromClientProvider(engine, credentials, accounts, platform), accounts),
        ReconnectPolicy(firstDelayMillis = 10, random = Random(0)),
    )

    private suspend fun signedIn() {
        accounts.save(account())
        credentials.write("a1", "osm_token")
    }

    private fun event(seq: Long, text: String) =
        """{"seq":$seq,"type":"message.agent","at":"2026-11-14T10:00:0${seq % 10}Z","epoch":1,"data":{"text":"$text"}}"""

    private fun frame(seq: Long, text: String) = "id: $seq\nevent: message.agent\ndata: ${event(seq, text)}\n\n"

    @Test
    fun theNewestPageIsAskedBeforeAnySequenceAndAFeedPageNamesItsFeed() = runTest {
        signedIn()
        answers += HttpStatusCode.OK to """{"events":[${event(4, "Hello")}],"lastSeq":9,"nextBefore":4}"""
        answers += HttpStatusCode.OK to """{"events":[]}"""
        val newest = repository.page("a1", "c1", before = null)
        assertEquals(listOf<TranscriptEvent>(TranscriptEvent.AgentMessage(4, 1_794_650_404_000, "Hello", null)), newest.events)
        assertEquals(4L, newest.nextBefore)
        assertEquals(9L, newest.lastSeq)
        assertEquals("/api/v1/cloud/sessions/c1/events", requests[0].url.encodedPath)
        assertEquals(Long.MAX_VALUE.toString(), requests[0].url.parameters["before"])
        assertNull(requests[0].url.parameters["feed"])
        val older = repository.feedPage("a1", "c1", before = 4, feed = "main")
        assertNull(older.nextBefore)
        assertEquals("4", requests[1].url.parameters["before"])
        assertEquals("main", requests[1].url.parameters["feed"])
    }

    @Test
    fun theSubagentsAreTheServersList() = runTest {
        signedIn()
        answers += HttpStatusCode.OK to """{"agents":[{"id":"toolu_a","type":"Explore","description":"Map","state":"completed","startedAt":"2026-11-14T10:00:00Z",
            "endedAt":"2026-11-14T10:01:00Z","steps":4}]}"""
        val agent = repository.agents("a1", "c1").single()
        assertEquals(KnownAgentState.COMPLETED, agent.state)
        assertEquals(60_000L, agent.endedAt!! - agent.startedAt)
        assertEquals("/api/v1/cloud/sessions/c1/agents", requests[0].url.encodedPath)
    }

    @Test
    fun theStreamResumesAfterTheLastEventAndEndsWithTheSession() = runTest {
        signedIn()
        answers += HttpStatusCode.OK to frame(5, "One")
        answers += HttpStatusCode.OK to frame(6, "Two") + "event: end\ndata: {}\n\n"
        val updates = repository.follow("a1", "c1", after = 4).toList()
        assertEquals(LiveUpdate.Opened(resumed = true), updates[0])
        assertEquals("One", assertIs<TranscriptEvent.AgentMessage>(assertIs<LiveUpdate.Item<TranscriptEvent>>(updates[1]).value).text)
        assertIs<LiveUpdate.Reconnecting>(updates[2], "a closed stream reconnects")
        assertEquals("Two", assertIs<TranscriptEvent.AgentMessage>(assertIs<LiveUpdate.Item<TranscriptEvent>>(updates[4]).value).text)
        assertEquals(LiveUpdate.Ended, updates.last(), "the session ended: nothing more to follow")
        assertEquals(2, requests.size)
        assertEquals("4", requests[0].headers["Last-Event-ID"])
        assertEquals("5", requests[1].headers["Last-Event-ID"], "the next connection resumes after the last event")
        assertEquals("/api/v1/cloud/sessions/c1/events/stream", requests[0].url.encodedPath)
    }

    @Test
    fun aGoneSessionEndsTheStreamWithoutRetrying() = runTest {
        signedIn()
        answers += HttpStatusCode.NotFound to """{"error":{"type":"not_found","message":"m"}}"""
        assertFailsWith<AppException.NotFound> { repository.follow("a1", "c1", after = 0).toList() }
        assertEquals(1, requests.size)
    }
}
