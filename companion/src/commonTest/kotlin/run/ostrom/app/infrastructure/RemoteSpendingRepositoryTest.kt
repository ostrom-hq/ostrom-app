// SPDX-License-Identifier: AGPL-3.0-only
// Shaped like TeamCode's companion RemoteWaitRepositoryTest (Apache-2.0); see NOTICE.
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
import run.ostrom.app.domain.models.ExceededBudget
import run.ostrom.app.domain.models.SpendLine
import run.ostrom.app.domain.models.SpendPeriod
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.infrastructure.client.OstromClientProvider
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.repositories.AccountCalls
import run.ostrom.app.infrastructure.repositories.RemoteActivityRepository
import run.ostrom.app.infrastructure.repositories.RemoteSpendingRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RemoteSpendingRepositoryTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val bodies = mutableMapOf<String, String>()
    private val accounts = accounts()
    private val credentials = MemoryCredentialStore()

    private val engine = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = MockEngine(MockEngineConfig().apply {
            block()
            addHandler { request ->
                requests += request
                val body = bodies[request.url.encodedPath]
                if (body == null) respond("""{"error":{"type":"not_found","message":"m"}}""", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
                else respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
    }
    private val platform = object : PlatformInfo {
        override val platform = "Test"
        override val deviceModel = "Test"
        override val appVersion = "1.0.0"
    }
    private val calls = AccountCalls(OstromClientProvider(engine, credentials, accounts, platform), accounts)

    private suspend fun signedIn() {
        accounts.save(account())
        credentials.write("a1", "osm_token")
    }

    @Test
    fun theReportOfThePeriodIsAskedAndMapped() = runTest {
        signedIn()
        bodies["/api/v1/spending"] = """
            {"period":"week","from":"2026-11-09T00:00:00Z","to":"2026-11-16T00:00:00Z","totalUsd":103.2,"budgetUsd":125.0,
             "byAgent":[{"key":"codex","label":"Codex","usd":27.9,"sessions":9}],"byRepo":[],
             "byPerson":[{"key":"m-ada","label":"ada","usd":60.1,"sessions":17}],"daily":[{"date":"2026-11-09","usd":10.0}],
             "exceeded":[{"scope":"member","subject":"m-ada","period":"day","usd":25.0,"hardStop":false,"since":"2026-11-13T09:00:00Z"}]}
        """.trimIndent()
        val report = RemoteSpendingRepository(calls).report("a1", SpendPeriod.WEEK)
        assertEquals("week", requests.single().url.parameters["period"])
        assertEquals(SpendPeriod.WEEK, report.period)
        assertEquals(103.2, report.totalUsd)
        assertEquals(125.0, report.budgetUsd)
        assertEquals(listOf(SpendLine("codex", "Codex", 27.9, 9)), report.byAgent)
        assertEquals(listOf(ExceededBudget("member", "m-ada", null, "day", 25.0, hardStop = false)), report.exceeded)
    }

    @Test
    fun aServerWithoutTheRouteFailsWithoutItsMessage() = runTest {
        signedIn()
        val error = assertFailsWith<AppException.Server> { RemoteSpendingRepository(calls).report("a1", SpendPeriod.MONTH) }
        assertEquals(404, error.statusCode)
    }

    @Test
    fun theActivityIsReadFromItsCursor() = runTest {
        signedIn()
        bodies["/api/v1/activity"] = """
            {"items":[{"id":"i1","at":"2026-11-13T10:00:00Z","kind":"session.ended","actor":{"kind":"agent","name":"Codex"},
              "summary":"Session ended","repo":"ae/web","costUsd":4.12,"approvalId":"apr1"}]}
        """.trimIndent()
        val page = RemoteActivityRepository(calls).page("a1", before = "c2")
        assertEquals("c2", requests.single().url.parameters["before"])
        assertNull(page.next)
        val item = page.items.single()
        assertEquals(1_794_564_000_000L, item.at)
        assertEquals("Codex", item.actorName)
        assertEquals(true, item.byAgent)
        assertEquals(4.12, item.costUsd)
        assertEquals("apr1", item.approvalId)
        assertEquals("ae/web", item.repo)
    }
}
