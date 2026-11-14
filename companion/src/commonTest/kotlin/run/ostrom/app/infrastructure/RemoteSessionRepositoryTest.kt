// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion RemoteSessionRepositoryTest and RemoteTodoRepositoryTest (Apache-2.0); see NOTICE.
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
import run.ostrom.app.domain.models.TodoItemState
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.infrastructure.client.OstromClientProvider
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.repositories.AccountCalls
import run.ostrom.app.infrastructure.repositories.RemoteSessionRepository
import run.ostrom.app.infrastructure.repositories.RemoteTodoRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteSessionRepositoryTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val answers = mutableMapOf<String, Pair<HttpStatusCode, String>>()
    private val accounts = accounts()
    private val credentials = MemoryCredentialStore()

    private val engine = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = MockEngine(MockEngineConfig().apply {
            block()
            addHandler { request ->
                requests += request
                val (status, body) = answers[request.url.encodedPath] ?: (HttpStatusCode.NotFound to """{"error":{"type":"not_found","message":"m"}}""")
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
    }
    private val platform = object : PlatformInfo {
        override val platform = "Test"
        override val deviceModel = "Test"
        override val appVersion = "1.0.0"
    }
    private val calls = AccountCalls(OstromClientProvider(engine, credentials, accounts, platform), accounts)
    private val sessions = RemoteSessionRepository(calls)
    private val todos = RemoteTodoRepository(calls)

    private val ended = """{"id":"s2","repo":"api","realRepo":"acme/api","agent":"codex","dev":"bob","model":"gpt-5","startedAt":"2026-11-13T09:00:00Z",
        "endedAt":"2026-11-13T10:00:00Z","endedBy":"SessionEnd hook","tools":42,"costUsd":3.25,"exitCode":0,"receiptId":"rc1","runId":"run1","newer":true}"""

    private suspend fun signedIn() {
        accounts.save(account())
        credentials.write("a1", "osm_token")
    }

    @Test
    fun aPageIsAskedInItsFilterFromItsCursorAndMapped() = runTest {
        signedIn()
        answers["/api/v1/sessions"] = HttpStatusCode.OK to """{"items":[
            {"id":"s1","agent":"claude-code","dev":"ada","startedAt":"2026-11-14T09:00:00Z","activity":"working"},$ended,
            {"id":"s3","agent":"claude-code","dev":"ada","startedAt":"2026-11-12T09:00:00Z","ended":true,"lost":true}],"next":"c3"}"""
        val page = sessions.sessions("a1", open = true, before = "c2")
        assertEquals("c3", page.next)
        val (open, done, lost) = page.sessions
        assertTrue(open.open)
        assertEquals("working", open.activity)
        assertNull(open.repo)
        assertEquals("acme/api", done.repo, "The real repository when the server knows it")
        assertEquals(42, done.tools); assertEquals(3.25, done.costUsd); assertEquals("rc1", done.receiptId); assertEquals("run1", done.runId)
        assertFalse(done.open)
        assertEquals(1_794_560_400_000L, done.startedAt)
        assertFalse(lost.open)
        assertTrue(lost.lost)
        val request = requests.single().url.parameters
        assertEquals("true", request["open"]); assertEquals("c2", request["before"]); assertEquals("50", request["limit"])
    }

    @Test
    fun oneSessionCarriesItsGateAnswersAndAGoneOneIsNotFound() = runTest {
        signedIn()
        answers["/api/v1/sessions/s2"] = HttpStatusCode.OK to """{"session":${ended.replace("\"newer\":true", "\"gateAnswers\":[{\"gateId\":\"g1\",\"actionClass\":\"git.push\",\"outcome\":\"approved\",\"by\":\"carol\",\"at\":\"2026-11-13T09:30:00Z\",\"receiptId\":\"rc0\"}]")},"receipt":null}"""
        val session = sessions.session("a1", "s2")
        val answer = session.gateAnswers.single()
        assertEquals("git.push", answer.actionClass); assertEquals("approved", answer.outcome); assertEquals("carol", answer.by); assertEquals("rc0", answer.receiptId)
        assertFailsWith<AppException.NotFound> { sessions.session("a1", "gone") }
    }

    @Test
    fun aTodoIsReadWithItsItemsProofsAndHookErrors() = runTest {
        signedIn()
        answers["/api/v1/todos/session"] = HttpStatusCode.OK to """{"todo":{"id":"t1","sessionId":"s1","reference":"The plan","status":"active","items":[
            {"id":"a","name":"Write the tests","description":"First","doneWhen":{"text":"CI green","checks":[{"type":"ci_green","repo":"acme/api"}]},"origin":"plan",
             "status":"done","proof":{"at":"2026-11-14T09:10:00Z","passed":true,"text":"CI passed","urls":["https://ci.example.org/1"]}},
            {"id":"b","name":"Ship","description":"","doneWhen":{"text":"merged","checks":[{"type":"future_check","x":1}]},"origin":"discovered","originReason":"Found later","status":"doing"},
            {"id":"c","name":"Old","description":"","doneWhen":{"text":"n/a","checks":[]},"origin":"plan","status":"dropped","dropReason":"Not needed"},
            {"id":"d","name":"New","description":"","doneWhen":{"text":"n/a","checks":[]},"origin":"plan","status":"paused","unverified":true}],
            "createdAt":"2026-11-14T09:00:00Z","updatedAt":"2026-11-14T09:20:00Z","hookEvents":[{"at":"2026-11-14T09:15:00Z","kind":"hook_error","text":"timeout"}]}}"""
        val todo = todos.todo("a1", "s1")!!
        assertEquals("The plan", todo.reference)
        assertEquals(listOf(TodoItemState.DONE, TodoItemState.DOING, TodoItemState.DROPPED, TodoItemState.UNKNOWN), todo.items.map { it.state })
        assertEquals("CI green", todo.items[0].doneWhen)
        assertEquals(true, todo.items[0].proof?.passed)
        assertTrue(todo.items[1].addedLater); assertEquals("Found later", todo.items[1].addedReason)
        assertEquals("Not needed", todo.items[2].dropReason)
        assertTrue(todo.items[3].unverified)
        assertEquals("hook_error", todo.hookEvents.single().kind)
        assertEquals("s1", requests.single().url.parameters["sessionId"])
        answers["/api/v1/todos/session"] = HttpStatusCode.OK to """{"todo":null}"""
        assertNull(todos.todo("a1", "s1"), "No todo while the agent has none")
    }
}
