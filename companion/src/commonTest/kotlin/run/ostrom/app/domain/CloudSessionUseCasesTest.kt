// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../domain/SessionActionUseCasesTest.kt; see NOTICE.
package run.ostrom.app.domain

import kotlinx.coroutines.test.runTest
import run.ostrom.app.FakeFeatures
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.CloudAgent
import run.ostrom.app.domain.models.CloudFailure
import run.ostrom.app.domain.models.CloudPhase
import run.ostrom.app.domain.models.CloudRecipe
import run.ostrom.app.domain.models.CloudRepository
import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.domain.models.CloudSessionPage
import run.ostrom.app.domain.models.CloudSessionProblem
import run.ostrom.app.domain.models.CloudSessionRefusal
import run.ostrom.app.domain.models.CloudSessionStatus
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.NewCloudSession
import run.ostrom.app.domain.repositories.CloudSessionRepository
import run.ostrom.app.domain.usecases.sessions.CloudSessionAccess
import run.ostrom.app.domain.usecases.sessions.ControlCloudSessionUseCase
import run.ostrom.app.domain.usecases.sessions.CreateCloudSessionUseCase
import run.ostrom.app.domain.usecases.sessions.GetCloudSessionUseCase
import run.ostrom.app.domain.usecases.sessions.LoadCloudSessionOptionsUseCase
import run.ostrom.app.domain.usecases.sessions.LoadCloudSessionsUseCase
import run.ostrom.app.domain.usecases.sessions.checkNewCloudSession
import run.ostrom.app.fakeGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun cloudSession(
    id: String = "c1", status: CloudSessionStatus = CloudSessionStatus.RUNNING, desired: String? = "running", phase: String? = null,
    failure: CloudFailure? = null,
) = CloudSession(id, "Mill", "claude", null, null, null, status, desired, phase, failure, null, 1_000L, 1_000L, null, null, null, null, null)

class CloudSessionUseCasesTest {
    private class FakeCloudSessions : CloudSessionRepository {
        val calls = mutableListOf<String>()
        var created: NewCloudSession? = null
        override suspend fun sessions(accountId: String, before: String?): CloudSessionPage {
            calls += "list ${before.orEmpty()}".trim()
            return CloudSessionPage(listOf(cloudSession()), "c2")
        }
        override suspend fun session(accountId: String, sessionId: String): CloudSession { calls += "get $sessionId"; return cloudSession(sessionId) }
        override suspend fun create(accountId: String, session: NewCloudSession): CloudSession { created = session; calls += "create"; return cloudSession("new") }
        override suspend fun pause(accountId: String, sessionId: String): CloudSession { calls += "pause $sessionId"; return cloudSession(sessionId, CloudSessionStatus.PAUSING, "paused") }
        override suspend fun resume(accountId: String, sessionId: String): CloudSession { calls += "resume $sessionId"; return cloudSession(sessionId, CloudSessionStatus.RESUMING) }
        override suspend fun stop(accountId: String, sessionId: String): CloudSession { calls += "stop $sessionId"; return cloudSession(sessionId, CloudSessionStatus.RUNNING, "terminated") }
    }

    private val sessions = FakeCloudSessions()
    private val features = FakeFeatures()
    private val access = CloudSessionAccess(sessions, features, fakeGuard())

    @Test
    fun aServerWithoutCloudSessionsRefusesEveryCall() = runTest {
        assertEquals(Features.RUNNERS_CLOUD, assertFailsWith<AppException.Unsupported> { LoadCloudSessionsUseCase(access)("a1") }.feature)
        assertFailsWith<AppException.Unsupported> { GetCloudSessionUseCase(access)("a1", "c1") }
        assertFailsWith<AppException.Unsupported> { LoadCloudSessionOptionsUseCase(access)("a1") }
        assertFailsWith<AppException.Unsupported> { CreateCloudSessionUseCase(access)("a1", NewCloudSession(CloudAgent.CLAUDE)) }
        assertTrue(sessions.calls.isEmpty())
    }

    @Test
    fun theListIsPagedAndARecordIsRead() = runTest {
        features.features = setOf(Features.RUNNERS_CLOUD)
        assertEquals("c2", LoadCloudSessionsUseCase(access)("a1").next)
        LoadCloudSessionsUseCase(access)("a1", before = "c2")
        assertEquals("c9", GetCloudSessionUseCase(access)("a1", "c9").id)
        assertFailsWith<AppException.NotFound> { GetCloudSessionUseCase(access)("a1", " ") }
        assertEquals(listOf("list", "list c2", "get c9"), sessions.calls)
    }

    @Test
    fun theFormTakesTheRecipeOnlyWhenTheServerAnnouncesIt() = runTest {
        features.features = setOf(Features.RUNNERS_CLOUD)
        assertFalse(LoadCloudSessionOptionsUseCase(access)("a1").recipe)
        features.features = setOf(Features.RUNNERS_CLOUD, Features.RUNNERS_CLOUD_ENVIRONMENT)
        assertTrue(LoadCloudSessionOptionsUseCase(access)("a1").recipe)
    }

    @Test
    fun withoutTheEnvironmentFeatureOnlyTheSingleRepositoryGoes() = runTest {
        features.features = setOf(Features.RUNNERS_CLOUD)
        val form = NewCloudSession(
            CloudAgent.CODEX, name = "  ", model = " gpt-5 ", prompt = "  ", repo = " https://github.com/acme/api ", ref = " main ",
            recipe = CloudRecipe(repositories = listOf(CloudRepository("https://github.com/acme/web"))),
        )
        CreateCloudSessionUseCase(access)("a1", form)
        assertEquals(NewCloudSession(CloudAgent.CODEX, model = "gpt-5", repo = "https://github.com/acme/api", ref = "main"), sessions.created)
    }

    @Test
    fun withTheEnvironmentFeatureTheRecipeIsTidiedAndSent() = runTest {
        features.features = setOf(Features.RUNNERS_CLOUD, Features.RUNNERS_CLOUD_ENVIRONMENT)
        val form = NewCloudSession(
            CloudAgent.CLAUDE, repo = "https://github.com/acme/ignored",
            recipe = CloudRecipe(
                repositories = listOf(CloudRepository(" https://github.com/acme/api.git ", " api ", " ", "fix"), CloudRepository("  ")),
                workingDirectory = " ", setupCreate = listOf("npm ci", " ", ""), setupStart = listOf(" make dev "),
                requiredVariables = listOf("NPM_TOKEN", "", "API_KEY"), requireGitHub = true, gitEmailDomain = " acme.dev ",
            ),
        )
        CreateCloudSessionUseCase(access)("a1", form)
        val sent = sessions.created!!
        assertNull(sent.repo, "A recipe replaces the single repository")
        assertEquals(
            CloudRecipe(listOf(CloudRepository("https://github.com/acme/api.git", "api", null, "fix")), null, listOf("npm ci"), listOf("make dev"),
                listOf("NPM_TOKEN", "API_KEY"), true, "acme.dev"),
            sent.recipe,
        )
        // An empty recipe falls back to the single repository.
        CreateCloudSessionUseCase(access)("a1", NewCloudSession(CloudAgent.SHELL, repo = "https://github.com/acme/api", recipe = CloudRecipe(listOf(CloudRepository("")))))
        assertEquals("https://github.com/acme/api", sessions.created?.repo)
        assertNull(sessions.created?.recipe)
    }

    @Test
    fun aFormThatBreaksARuleIsRefusedBeforeAnyRequest() {
        fun problem(form: NewCloudSession, recipe: Boolean = true) = assertFailsWith<AppException.CloudSessionInvalid> { checkNewCloudSession(form, recipe) }.problem
        assertEquals(CloudSessionProblem.NAME, problem(NewCloudSession(CloudAgent.CLAUDE, name = "x".repeat(121))))
        assertEquals(CloudSessionProblem.NAME, problem(NewCloudSession(CloudAgent.CLAUDE, name = "a\u0007b")))
        assertEquals(CloudSessionProblem.PROMPT, problem(NewCloudSession(CloudAgent.CLAUDE, prompt = "x".repeat(20_001))))
        assertEquals(CloudSessionProblem.REPOSITORY, problem(NewCloudSession(CloudAgent.CLAUDE, repo = "https://gitlab.com/acme/api"), recipe = false))
        assertEquals(CloudSessionProblem.REPOSITORY, problem(NewCloudSession(CloudAgent.CLAUDE, recipe = CloudRecipe(listOf(CloudRepository("git@github.com:acme/api"))))))
        assertEquals(CloudSessionProblem.TOO_MANY,
            problem(NewCloudSession(CloudAgent.CLAUDE, recipe = CloudRecipe(List(17) { CloudRepository("https://github.com/acme/r$it") }))))
        assertEquals(CloudSessionProblem.TOO_MANY, problem(NewCloudSession(CloudAgent.CLAUDE, recipe = CloudRecipe(setupStart = List(17) { "echo $it" }))))
        assertEquals(CloudSessionProblem.VARIABLES, problem(NewCloudSession(CloudAgent.CLAUDE, recipe = CloudRecipe(requiredVariables = listOf("lower")))))
        assertEquals(CloudSessionProblem.VARIABLES, problem(NewCloudSession(CloudAgent.CLAUDE, recipe = CloudRecipe(requiredVariables = listOf("A", "A")))))
        assertEquals(CloudSessionProblem.VARIABLES, problem(NewCloudSession(CloudAgent.CLAUDE, recipe = CloudRecipe(requiredVariables = List(65) { "V$it" }))))
        assertEquals(CloudSessionProblem.TOO_MANY, problem(NewCloudSession(CloudAgent.CLAUDE, recipe = CloudRecipe(setupCreate = listOf("x".repeat(4097))))))
        assertEquals(CloudSessionProblem.EMAIL_DOMAIN, problem(NewCloudSession(CloudAgent.CLAUDE, recipe = CloudRecipe(gitEmailDomain = "Acme.dev"))))
        // A row with a directory but no address is refused, never dropped.
        assertEquals(CloudSessionProblem.REPOSITORY, problem(NewCloudSession(CloudAgent.CLAUDE, recipe = CloudRecipe(listOf(CloudRepository(" ", path = "api"))))))
    }

    @Test
    fun eachActionIsOfferedOnlyInTheStatesTheServerTakesIt() = runTest {
        features.features = setOf(Features.RUNNERS_CLOUD)
        val control = ControlCloudSessionUseCase(access)
        val running = cloudSession()
        val paused = cloudSession("p", CloudSessionStatus.PAUSED, "paused")
        val ended = cloudSession("e", CloudSessionStatus.TERMINATED, "terminated")
        assertTrue(running.canPause && running.canStop && !running.canResume)
        assertTrue(paused.canResume && paused.canStop && !paused.canPause)
        assertFalse(ended.canPause || ended.canResume || ended.canStop)
        assertFalse(cloudSession(desired = "terminated").canStop, "A stop already asked")
        assertFalse(cloudSession(status = CloudSessionStatus.PAUSED, desired = "running").canResume, "A resume already asked")
        assertEquals(CloudSessionStatus.PAUSING, control.pause("a1", running).status)
        assertEquals(CloudSessionStatus.RESUMING, control.resume("a1", paused).status)
        control.stop("a1", paused)
        assertEquals(CloudSessionRefusal.STATE, assertFailsWith<AppException.CloudSessionRefused> { control.pause("a1", paused) }.reason)
        assertEquals(CloudSessionRefusal.STATE, assertFailsWith<AppException.CloudSessionRefused> { control.resume("a1", running) }.reason)
        assertEquals(CloudSessionRefusal.STATE, assertFailsWith<AppException.CloudSessionRefused> { control.stop("a1", ended) }.reason)
        assertEquals(listOf("pause c1", "resume p", "stop p"), sessions.calls)
    }

    @Test
    fun aPhaseIsReadOnlyWhileTheSessionIsPrepared() {
        assertEquals(CloudPhase.CLONING_REPOSITORIES, cloudSession(status = CloudSessionStatus.CREATING, phase = "cloning_repositories").preparationPhase)
        assertEquals(CloudPhase.RUNNING_SETUP, cloudSession(status = CloudSessionStatus.RESUMING, phase = "running_setup").preparationPhase)
        assertNull(cloudSession(status = CloudSessionStatus.CREATING, phase = "scheduling").preparationPhase)
        assertTrue(cloudSession(status = CloudSessionStatus.CREATING, phase = "scheduling").preparing)
        assertNull(cloudSession(phase = "running_setup").preparationPhase)
        assertEquals(CloudSessionStatus.UNKNOWN, CloudSessionStatus.of("hibernating"))
        assertTrue(CloudFailure(CloudFailure.PREPARATION_FAILED, "m", true).preparation)
        assertFalse(CloudFailure("idle_timeout", "m", null).preparation)
    }
}
