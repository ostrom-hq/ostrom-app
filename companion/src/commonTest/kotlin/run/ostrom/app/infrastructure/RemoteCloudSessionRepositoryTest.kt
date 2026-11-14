// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../infrastructure/RemoteSessionRepositoryTest.kt; see NOTICE.
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
import kotlinx.coroutines.test.runTest
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.CloudAgent
import run.ostrom.app.domain.models.CloudPhase
import run.ostrom.app.domain.models.CloudRecipe
import run.ostrom.app.domain.models.CloudRepository
import run.ostrom.app.domain.models.CloudSessionRefusal
import run.ostrom.app.domain.models.CloudSessionStatus
import run.ostrom.app.domain.models.NewCloudSession
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.infrastructure.client.OstromClientProvider
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.repositories.AccountCalls
import run.ostrom.app.infrastructure.repositories.RemoteCloudSessionRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteCloudSessionRepositoryTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val answers = mutableMapOf<String, Pair<HttpStatusCode, String>>()
    private val accounts = accounts()
    private val credentials = MemoryCredentialStore()

    private val engine = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = MockEngine(MockEngineConfig().apply {
            block()
            addHandler { request ->
                requests += request
                val key = "${request.method.value} ${request.url.encodedPath}"
                val (status, body) = answers[key] ?: (HttpStatusCode.NotFound to """{"error":{"type":"not_found","message":"m"}}""")
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
    }
    private val platform = object : PlatformInfo {
        override val platform = "Test"
        override val deviceModel = "Test"
        override val appVersion = "1.0.0"
    }
    private val repository = RemoteCloudSessionRepository(AccountCalls(OstromClientProvider(engine, credentials, accounts, platform), accounts))

    private fun session(id: String, status: String, extra: String = "") = """{"id":"$id","name":"Mill","owner":"m1","agent":"claude","model":"claude-opus",
        "status":"$status","desiredStatus":"running","epoch":1,"limits":{"idleTimeoutMinutes":30,"maxRunMinutes":240,"restrictedEgress":true},
        "createdAt":"2026-11-14T09:00:00Z","updatedAt":"2026-11-14T09:01:00Z","githubAccess":"owner"$extra}"""

    private suspend fun signedIn() {
        accounts.save(account())
        credentials.write("a1", "osm_token")
    }

    @Test
    fun theSessionsArePagedAndARecordCarriesItsPhaseFailureAndRecipe() = runTest {
        signedIn()
        answers["GET /api/v1/cloud/sessions"] = HttpStatusCode.OK to """{"items":[${session("c1", "running")},${session("c2", "hibernating")}],"next":"c2"}"""
        val recipe = ""","phase":"cloning_repositories","failure":{"code":"preparation_failed","message":"Cloning repository 1 failed.","retryable":true},
            "environment":{"repositories":[{"url":"https://github.com/acme/api","path":"api","ref":"main","branch":"fix"}],"workingDirectory":"api",
            "setup":{"create":["npm ci"],"start":["make dev"]},"requiredVariables":["NPM_TOKEN"],"requireGitHub":true,"gitEmailDomain":"acme.dev"}"""
        answers["GET /api/v1/cloud/sessions/c1"] = HttpStatusCode.OK to session("c1", "creating", recipe)
        val page = repository.sessions("a1", before = "c0")
        assertEquals("c2", page.next)
        assertEquals(listOf(CloudSessionStatus.RUNNING, CloudSessionStatus.UNKNOWN), page.sessions.map { it.status })
        assertEquals("c0", requests[0].url.parameters["before"])
        val record = repository.session("a1", "c1")
        assertEquals(CloudPhase.CLONING_REPOSITORIES, record.preparationPhase)
        assertEquals(true, record.failure?.retryable)
        assertTrue(record.failure!!.preparation)
        assertEquals(30, record.limits?.idleTimeoutMinutes)
        assertEquals(1_794_646_800_000L, record.createdAt)
        assertEquals(
            CloudRecipe(listOf(CloudRepository("https://github.com/acme/api", "api", "main", "fix")), "api", listOf("npm ci"), listOf("make dev"),
                listOf("NPM_TOKEN"), true, "acme.dev"),
            record.recipe,
        )
        assertFailsWith<AppException.NotFound> { repository.session("a1", "gone") }
    }

    @Test
    fun aSessionStartsWithTheSingleRepositoryOrItsRecipe() = runTest {
        signedIn()
        answers["POST /api/v1/cloud/sessions"] = HttpStatusCode.Created to session("c9", "creating")
        assertEquals("c9", repository.create("a1", NewCloudSession(CloudAgent.CODEX, name = "Fix", repo = "https://github.com/acme/api", ref = "main")).id)
        val single = (requests.last().body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertTrue(""""agent":"codex"""" in single && """"repo":"https://github.com/acme/api"""" in single && """"ref":"main"""" in single, single)
        assertFalse("environment" in single, single)
        repository.create("a1", NewCloudSession(CloudAgent.CLAUDE, recipe = CloudRecipe(listOf(CloudRepository("https://github.com/acme/api", "api")),
            setupCreate = listOf("npm ci"), requiredVariables = listOf("NPM_TOKEN"), requireGitHub = true)))
        val withRecipe = (requests.last().body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertTrue(""""repositories":[{"url":"https://github.com/acme/api","path":"api"""" in withRecipe, withRecipe)
        assertTrue(""""create":["npm ci"]""" in withRecipe && """"requiredVariables":["NPM_TOKEN"]""" in withRecipe && """"requireGitHub":true""" in withRecipe, withRecipe)
    }

    @Test
    fun pauseResumeAndStopTakeTheServersAnswer() = runTest {
        signedIn()
        answers["POST /api/v1/cloud/sessions/c1/pause"] = HttpStatusCode.Accepted to session("c1", "pausing")
        answers["POST /api/v1/cloud/sessions/c1/resume"] = HttpStatusCode.Accepted to session("c1", "resuming")
        answers["DELETE /api/v1/cloud/sessions/c1"] = HttpStatusCode.Accepted to session("c1", "running")
        assertEquals(CloudSessionStatus.PAUSING, repository.pause("a1", "c1").status)
        assertEquals(CloudSessionStatus.RESUMING, repository.resume("a1", "c1").status)
        assertEquals(CloudSessionStatus.RUNNING, repository.stop("a1", "c1").status)
        assertEquals("DELETE /api/v1/cloud/sessions/c1", requests.last().let { "${it.method.value} ${it.url.encodedPath}" })
        assertFailsWith<AppException.NotFound> { repository.pause("a1", "gone") }
    }

    @Test
    fun eachRefusalIsKnownByItsKeyNeverByTheServersText() = runTest {
        signedIn()
        suspend fun refusal(status: HttpStatusCode, type: String): CloudSessionRefusal? {
            answers["POST /api/v1/cloud/sessions/c1/resume"] = status to """{"error":{"type":"$type","message":"Your plan runs at most 2 cloud sessions"}}"""
            return try {
                repository.resume("a1", "c1"); null
            } catch (error: AppException.CloudSessionRefused) {
                assertFalse("plan runs" in error.message.orEmpty())
                error.reason
            }
        }
        assertEquals(CloudSessionRefusal.PLAN_LIMIT, refusal(HttpStatusCode.PaymentRequired, "payment_required"))
        assertEquals(CloudSessionRefusal.STATE, refusal(HttpStatusCode.Conflict, "conflict"))
        assertEquals(CloudSessionRefusal.FORBIDDEN, refusal(HttpStatusCode.Forbidden, "forbidden"))
        assertEquals(CloudSessionRefusal.PROFILE_REQUIRED, refusal(HttpStatusCode.UnprocessableEntity, "profile_required"))
        assertEquals(CloudSessionRefusal.PROFILE_UNAVAILABLE, refusal(HttpStatusCode.Forbidden, "profile_unassigned"))
        assertEquals(CloudSessionRefusal.PROFILE_UNAVAILABLE, refusal(HttpStatusCode.NotFound, "cloud_profile_not_found"))
        assertEquals(CloudSessionRefusal.PROFILE_UNAVAILABLE, refusal(HttpStatusCode.Forbidden, "image_not_allowed"))
        assertEquals(CloudSessionRefusal.CLUSTER_UNAVAILABLE, refusal(HttpStatusCode.Conflict, "cluster_unavailable"))
        assertEquals(CloudSessionRefusal.CLUSTER_UNAVAILABLE, refusal(HttpStatusCode.Conflict, "namespace_not_allowed"))
        // Only a start has values to check: an action's invalid_request stays a plain server error.
        answers["POST /api/v1/cloud/sessions/c1/resume"] = HttpStatusCode.BadRequest to """{"error":{"type":"invalid_request","message":"m"}}"""
        assertEquals(400, assertFailsWith<AppException.Server> { repository.resume("a1", "c1") }.statusCode)
        answers["POST /api/v1/cloud/sessions"] = HttpStatusCode.BadRequest to """{"error":{"type":"invalid_request","message":"m"}}"""
        assertEquals(CloudSessionRefusal.INVALID,
            assertFailsWith<AppException.CloudSessionRefused> { repository.create("a1", NewCloudSession(CloudAgent.CLAUDE)) }.reason)
        answers["POST /api/v1/cloud/sessions/c1/resume"] = HttpStatusCode.ServiceUnavailable to """{"error":{"type":"unavailable","message":"m"}}"""
        assertEquals(503, assertFailsWith<AppException.Server> { repository.resume("a1", "c1") }.statusCode)
    }
}
