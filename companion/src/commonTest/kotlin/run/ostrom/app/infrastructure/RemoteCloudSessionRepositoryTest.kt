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
import run.ostrom.app.domain.models.CloudSessionMode
import run.ostrom.app.domain.models.CloudSessionStatus
import run.ostrom.app.domain.models.InputReceipt
import run.ostrom.app.domain.models.PermissionAnswer
import run.ostrom.app.domain.models.QuestionAnswer
import run.ostrom.app.domain.models.SessionWaiting
import run.ostrom.app.domain.models.GitHubAccess
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
        assertEquals("c9", repository.create("a1", NewCloudSession(CloudAgent.CODEX, name = "Fix", repo = "https://github.com/acme/api", ref = "main"), "press-1").id)
        assertEquals("press-1", requests.last().headers["Idempotency-Key"])
        val single = (requests.last().body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertTrue(""""agent":"codex"""" in single && """"repo":"https://github.com/acme/api"""" in single && """"ref":"main"""" in single, single)
        assertFalse("environment" in single, single)
        repository.create("a1", NewCloudSession(CloudAgent.CLAUDE, recipe = CloudRecipe(listOf(CloudRepository("https://github.com/acme/api", "api")),
            setupCreate = listOf("npm ci"), requiredVariables = listOf("NPM_TOKEN"), requireGitHub = true)), "press-2")
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
            assertFailsWith<AppException.CloudSessionRefused> { repository.create("a1", NewCloudSession(CloudAgent.CLAUDE), "press-3") }.reason)
        answers["POST /api/v1/cloud/sessions/c1/resume"] = HttpStatusCode.ServiceUnavailable to """{"error":{"type":"unavailable","message":"m"}}"""
        assertEquals(503, assertFailsWith<AppException.Server> { repository.resume("a1", "c1") }.statusCode)
    }

    @Test
    fun theGitHubAccessIsPutAndItsRefusalsAreWordedByTheirKey() = runTest {
        signedIn()
        answers["PUT /api/v1/cloud/sessions/c1/github"] = HttpStatusCode.OK to session("c1", "paused").replace(""""githubAccess":"owner"""", """"githubAccess":"none"""")
        val changed = repository.setGitHubAccess("a1", "c1", GitHubAccess.NONE)
        assertEquals(GitHubAccess.NONE, changed.githubAccess)
        assertEquals("""{"access":"none"}""", (requests.last().body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
        assertEquals(GitHubAccess.OWNER, GitHubAccess.of("owner"))
        assertEquals(null, GitHubAccess.of("something_new"))
        val refusals = mapOf(
            "session_not_paused" to (HttpStatusCode.Conflict to CloudSessionRefusal.GITHUB_NOT_PAUSED),
            "github_owner_only" to (HttpStatusCode.Forbidden to CloudSessionRefusal.GITHUB_OWNER_ONLY),
            "conflict" to (HttpStatusCode.Conflict to CloudSessionRefusal.STATE),
        )
        for ((key, expected) in refusals) {
            answers["PUT /api/v1/cloud/sessions/c1/github"] = expected.first to """{"error":{"type":"$key","message":"server text"}}"""
            val refused = assertFailsWith<AppException.CloudSessionRefused> { repository.setGitHubAccess("a1", "c1", GitHubAccess.NONE) }
            assertEquals(expected.second, refused.reason)
            assertFalse("server text" in refused.message.orEmpty())
        }
    }

    private fun input(id: String, kind: String, status: String = "queued", woke: Boolean = false, reason: String? = null) =
        """{"id":"$id","kind":"$kind","status":"$status","authorId":"m1","woke":$woke,"createdAt":"2026-11-14T10:00:00Z"""" +
            (reason?.let { ""","reason":"$it"""" } ?: "") + "}"

    private fun body() = (requests.last().body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    @Test
    fun aConversationSessionCarriesItsModeEffortBypassAndWaiting() = runTest {
        signedIn()
        val extra = ""","mode":"conversation","effort":"high","permissionBypass":true,
            "waiting":{"reason":"permission","requestId":"r1","since":"2026-11-14T09:00:00Z","eventSeq":12}"""
        answers["GET /api/v1/cloud/sessions/c1"] = HttpStatusCode.OK to session("c1", "running", extra)
        answers["GET /api/v1/cloud/sessions/c2"] = HttpStatusCode.OK to session("c2", "running")
        val talk = repository.session("a1", "c1")
        assertEquals(CloudSessionMode.CONVERSATION, talk.mode)
        assertTrue(talk.conversation)
        assertEquals("high", talk.effort)
        assertTrue(talk.permissionBypass)
        assertEquals(SessionWaiting("permission", "r1", 1_794_646_800_000L), talk.waiting)
        val terminal = repository.session("a1", "c2")
        assertEquals(CloudSessionMode.TERMINAL, terminal.mode)
        assertFalse(terminal.conversation)
        assertFalse(terminal.permissionBypass)
        answers["POST /api/v1/cloud/sessions"] = HttpStatusCode.Created to session("c9", "creating")
        repository.create("a1", NewCloudSession(CloudAgent.CLAUDE, mode = CloudSessionMode.CONVERSATION), "press-1")
        assertTrue(""""mode":"conversation"""" in body(), body())
        repository.create("a1", NewCloudSession(CloudAgent.CLAUDE), "press-2")
        assertFalse("mode" in body(), body())
    }

    @Test
    fun messagesAnswersInterruptsAndStopsGoToTheirRoutes() = runTest {
        signedIn()
        answers["POST /api/v1/cloud/sessions/c1/messages"] = HttpStatusCode.Accepted to input("in-1", "message", woke = true)
        val receipt = repository.send("a1", "c1", "Run the tests", "client-1")
        assertEquals(InputReceipt("in-1", "queued", woke = true, reason = null), receipt)
        assertEquals("client-1", requests.last().headers["Idempotency-Key"])
        assertTrue(""""text":"Run the tests"""" in body() && "clientMessageId" !in body(), body())

        answers["POST /api/v1/cloud/sessions/c1/interrupt"] = HttpStatusCode.Accepted to input("int-1", "interrupt")
        assertEquals("int-1", repository.interrupt("a1", "c1").inputId)
        answers["POST /api/v1/cloud/sessions/c1/interrupt"] = HttpStatusCode.Conflict to """{"error":{"type":"nothing_to_interrupt","message":"m"}}"""
        val nothing = assertFailsWith<AppException.Server> { repository.interrupt("a1", "c1") }
        assertEquals(409, nothing.statusCode)
        assertEquals("nothing_to_interrupt", nothing.code)

        answers["POST /api/v1/cloud/sessions/c1/permissions"] = HttpStatusCode.Accepted to input("ans-1", "permission_answer", reason = null)
        repository.answer("a1", "c1", PermissionAnswer("r1", allow = true, answers = listOf(QuestionAnswer(0, listOf(1)), QuestionAnswer(1, text = "Grace"))), "key-1")
        assertEquals("key-1", requests.last().headers["Idempotency-Key"])
        assertTrue(""""requestId":"r1"""" in body() && """"behavior":"allow"""" in body() && """"answers":[{"question":0,"options":[1]},""" in body(), body())
        repository.answer("a1", "c1", PermissionAnswer("r2", allow = false), "key-2")
        assertTrue(""""behavior":"deny"""" in body() && "answers" !in body(), body())

        answers["POST /api/v1/cloud/sessions/c1/tasks/stop"] = HttpStatusCode.Accepted to input("stop-1", "task_stop", status = "rejected", reason = "task_stop_unsupported")
        assertEquals(InputReceipt("stop-1", "rejected", woke = false, reason = "task_stop_unsupported"), repository.stopTask("a1", "c1", "b1"))
        assertEquals("""{"taskId":"b1"}""", body())
    }

    @Test
    fun bypassTheModelAndTheEffortArePutAndAnswerTheSession() = runTest {
        signedIn()
        answers["PUT /api/v1/cloud/sessions/c1/permission-bypass"] = HttpStatusCode.OK to session("c1", "running", ""","mode":"conversation","permissionBypass":true""")
        assertTrue(repository.setPermissionBypass("a1", "c1", true).permissionBypass)
        assertEquals("""{"enabled":true}""", body())
        answers["PUT /api/v1/cloud/sessions/c1/model"] = HttpStatusCode.OK to session("c1", "running")
        assertEquals("claude-opus", repository.setModel("a1", "c1", "claude-opus").model)
        assertEquals("""{"model":"claude-opus"}""", body())
        answers["PUT /api/v1/cloud/sessions/c1/effort"] = HttpStatusCode.OK to session("c1", "running", ""","effort":"max"""")
        assertEquals("max", repository.setEffort("a1", "c1", "max").effort)
        assertEquals("""{"effort":"max"}""", body())
        answers["PUT /api/v1/cloud/sessions/c1/permission-bypass"] = HttpStatusCode.Forbidden to """{"error":{"type":"bypass_owner_only","message":"m"}}"""
        assertEquals("bypass_owner_only", assertFailsWith<AppException.Server> { repository.setPermissionBypass("a1", "c1", true) }.code)
    }
}
