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
import kotlinx.coroutines.test.runTest
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.McpProcess
import run.ostrom.app.domain.models.McpTransport
import run.ostrom.app.domain.models.PersonalMcpServer
import run.ostrom.app.domain.models.PersonalVariant
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.infrastructure.client.OstromClientProvider
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.repositories.AccountCalls
import run.ostrom.app.infrastructure.repositories.RemoteMemberSetupRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RemoteMemberSetupRepositoryTest {
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
    fun theEnvironmentGivesNamesWithoutValuesAndThePersonalVariants() = runTest {
        signedIn()
        bodies["/api/v1/environment"] = """{"variables":{"ZED":"secret-z","API_KEY":"secret-a"},"unavailable":["TOKEN"]}"""
        bodies["/api/v1/me/environment/variants"] = """{"items":[{"id":"v1","variable":"API_KEY","name":"Mine","binding":"shared",
            |"owner":"m1","hasValue":true,"version":1,"createdAt":"2026-11-13T10:00:00Z","updatedAt":"2026-11-13T10:00:00Z"}]}""".trimMargin()
        val environment = RemoteMemberSetupRepository(calls).environment("a1")
        assertEquals(listOf("API_KEY", "ZED"), environment.names)
        assertEquals(listOf("TOKEN"), environment.unavailable)
        assertEquals(listOf(PersonalVariant("v1", "API_KEY", "Mine", true, 1_794_564_000_000L)), environment.variants)
        assertFalse(environment.toString().contains("secret"))
        assertEquals(listOf("/api/v1/environment", "/api/v1/me/environment/variants"), requests.map { it.url.encodedPath })
    }

    @Test
    fun thePersonalMcpServersAreMappedByName() = runTest {
        signedIn()
        bodies["/api/v1/me/mcp/servers"] = """{"items":[
            |{"name":"notes","url":"stdio:npm:notes@1.0.0","transport":"stdio","enabled":true,"scope":"org","agentName":"ostrom-notes",
            | "state":"failed","failure":"start_failed","secretNames":["A"],"stdio":{"launcher":"npm","packageName":"notes@1.0.0","secretEnv":{"TOKEN":"B"}}},
            |{"name":"linear","url":"https://user:pw@mcp.linear.app:8443/mcp?token=abc#x","transport":"http","enabled":false,"scope":"org","state":"ready"}]}""".trimMargin()
        val servers = RemoteMemberSetupRepository(calls).mcpServers("a1")
        assertEquals(
            listOf(
                PersonalMcpServer("linear", null, McpTransport.HTTP, "https://mcp.linear.app:8443/mcp", null, null, emptyList(), false, McpProcess.Untracked),
                PersonalMcpServer("notes", "ostrom-notes", McpTransport.STDIO, null, "npm", "notes@1.0.0", listOf("A", "B"), true, McpProcess.Failed("start_failed")),
            ),
            servers,
        )
    }

    @Test
    fun aServerWithoutTheRoutesFailsWithoutItsMessage() = runTest {
        signedIn()
        assertEquals(404, assertFailsWith<AppException.Server> { RemoteMemberSetupRepository(calls).mcpServers("a1") }.statusCode)
    }
}
