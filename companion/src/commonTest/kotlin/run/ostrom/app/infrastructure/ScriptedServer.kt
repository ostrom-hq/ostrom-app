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
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.infrastructure.client.OstromClientProvider
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.repositories.AccountCalls

/** A scripted Ostrom server for the repositories' tests: one queued answer per request, every request recorded. */
class ScriptedServer {
    val requests = mutableListOf<HttpRequestData>()
    private class Answer(val status: HttpStatusCode, val body: String, val contentType: String)

    /** One entry per request: a scripted answer, or the failure the engine throws instead of answering. */
    private val answers = ArrayDeque<Any>()
    val accounts = accounts()
    val credentials = MemoryCredentialStore()
    val engine = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = MockEngine(MockEngineConfig().apply {
            block()
            addHandler { request ->
                requests += request
                when (val next = answers.removeFirstOrNull()) {
                    is Throwable -> throw next
                    is Answer -> respond(next.body, next.status, headersOf(HttpHeaders.ContentType, next.contentType))
                    else -> respond("{}", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        })
    }
    val platform = object : PlatformInfo {
        override val platform = "Test"
        override val deviceModel = "Test"
        override val appVersion = "1.0.0"
    }
    val provider = OstromClientProvider(engine, credentials, accounts, platform)
    val calls = AccountCalls(provider, accounts)

    suspend fun signedIn() {
        accounts.save(account())
        credentials.write("a1", "osm_token")
    }

    fun json(body: String, status: HttpStatusCode = HttpStatusCode.OK) = answers.addLast(Answer(status, body, "application/json"))

    /** An event stream of [body] (server-sent events). */
    fun stream(body: String) = answers.addLast(Answer(HttpStatusCode.OK, body, "text/event-stream"))

    /** The next request fails in the engine, as a connection that drops does. */
    fun fail(error: Throwable) = answers.addLast(error)

    fun bodyOf(request: HttpRequestData): String = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
}
