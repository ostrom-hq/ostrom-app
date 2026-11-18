// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../domain/SessionActionUseCasesTest.kt; see NOTICE.
package run.ostrom.app.domain

import kotlinx.coroutines.test.runTest
import run.ostrom.app.FakeFeatures
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.SessionHost
import run.ostrom.app.domain.models.SessionKind
import run.ostrom.app.domain.models.SessionKinds
import run.ostrom.app.domain.models.CloudAgent
import run.ostrom.app.domain.models.CloudFailure
import run.ostrom.app.domain.models.CloudPhase
import run.ostrom.app.domain.models.CloudRecipe
import run.ostrom.app.domain.models.CloudRepository
import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.domain.models.CloudSessionMode
import run.ostrom.app.domain.models.CloudSessionPage
import run.ostrom.app.domain.models.CloudSessionProblem
import run.ostrom.app.domain.models.CloudSessionRefusal
import run.ostrom.app.domain.models.CloudSessionStatus
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.GitHubAccess
import run.ostrom.app.domain.models.NewCloudSession
import run.ostrom.app.domain.usecases.sessions.CloudSessionAccess
import run.ostrom.app.domain.usecases.sessions.ControlConversationUseCase
import run.ostrom.app.domain.usecases.sessions.ControlCloudSessionUseCase
import run.ostrom.app.domain.usecases.sessions.CreateCloudSessionUseCase
import run.ostrom.app.domain.usecases.sessions.GetCloudSessionUseCase
import run.ostrom.app.domain.usecases.sessions.LoadCloudSessionOptionsUseCase
import run.ostrom.app.domain.usecases.sessions.LoadCloudSessionsUseCase
import run.ostrom.app.domain.usecases.sessions.SetCloudSessionGitHubAccessUseCase
import run.ostrom.app.domain.usecases.sessions.checkNewCloudSession
import run.ostrom.app.ConversationCalls
import run.ostrom.app.fakeGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun cloudSession(
    id: String = "c1", status: CloudSessionStatus = CloudSessionStatus.RUNNING, desired: String? = "running", phase: String? = null,
    failure: CloudFailure? = null, ownerId: String? = null, remote: Boolean = false, kind: SessionKind = if (remote) SessionKind.REMOTE else SessionKind.CLOUD,
    host: SessionHost? = null,
) = CloudSession(id, "Mill", "claude", null, null, null, status, desired, phase, failure, null, 1_000L, 1_000L, null, null, null, null, null, ownerId = ownerId, kind = kind,
    host = host)

class CloudSessionUseCasesTest {
    private class FakeCloudSessions : ConversationCalls() {
        override fun current(sessionId: String) = cloudSession(sessionId)
        var created: NewCloudSession? = null
        override suspend fun sessions(accountId: String, before: String?, kinds: Set<SessionKind>): CloudSessionPage {
            calls += ("list ${before.orEmpty()}".trim() + kinds.joinToString(",", prefix = " ") { it.wire }).trim()
            return CloudSessionPage(listOf(cloudSession()), "c2")
        }
        override suspend fun session(accountId: String, sessionId: String): CloudSession { calls += "get $sessionId"; return cloudSession(sessionId) }
        override suspend fun create(accountId: String, session: NewCloudSession, idempotencyKey: String): CloudSession {
            created = session; calls += "create $idempotencyKey"; return cloudSession("new")
        }
        override suspend fun pause(accountId: String, sessionId: String): CloudSession { calls += "pause $sessionId"; return cloudSession(sessionId, CloudSessionStatus.PAUSING, "paused") }
        override suspend fun resume(accountId: String, sessionId: String): CloudSession { calls += "resume $sessionId"; return cloudSession(sessionId, CloudSessionStatus.RESUMING) }
        override suspend fun stop(accountId: String, sessionId: String): CloudSession { calls += "stop $sessionId"; return cloudSession(sessionId, CloudSessionStatus.RUNNING, "terminated") }
        override suspend fun setGitHubAccess(accountId: String, sessionId: String, access: GitHubAccess): CloudSession {
            calls += "github $sessionId ${access.wire}"
            return cloudSession(sessionId).copy(githubAccess = access)
        }
    }

    private val sessions = FakeCloudSessions()
    private val features = FakeFeatures()
    private val access = CloudSessionAccess(sessions, features, fakeGuard())

    @Test
    fun aServerWithoutCloudSessionsRefusesEveryCall() = runTest {
        assertEquals(Features.RUNNERS_CLOUD, assertFailsWith<AppException.Unsupported> { LoadCloudSessionsUseCase(access)("a1") }.feature)
        assertFailsWith<AppException.Unsupported> { GetCloudSessionUseCase(access)("a1", "c1") }
        assertFailsWith<AppException.Unsupported> { LoadCloudSessionOptionsUseCase(access)("a1") }
        assertFailsWith<AppException.Unsupported> { CreateCloudSessionUseCase(access)("a1", NewCloudSession(CloudAgent.CLAUDE), "k1") }
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
    fun aServerWithRemoteSessionsIsAskedForEveryKindItKnowsAndAnOlderOneAsBefore() = runTest {
        features.features = setOf(Features.RUNNERS_CLOUD)
        assertFalse(LoadCloudSessionsUseCase(access)("a1").remoteSessions)
        features.features = setOf(Features.RUNNERS_CLOUD, Features.RUNNERS_CLOUD_REMOTE)
        assertTrue(LoadCloudSessionsUseCase(access)("a1").remoteSessions)
        features.features = setOf(Features.RUNNERS_CLOUD, Features.RUNNERS_CLOUD_REMOTE, Features.RUNNERS_CLOUD_LOCAL)
        LoadCloudSessionsUseCase(access)("a1")
        assertEquals(listOf("list", "list cloud,remote", "list cloud,remote,local"), sessions.calls)
        assertEquals(setOf(SessionKind.CLOUD, SessionKind.REMOTE), SessionKinds.announced(setOf(Features.RUNNERS_CLOUD_REMOTE)))
        assertEquals(SessionKind.CLOUD, SessionKind.of(null))
        assertEquals(SessionKind.CLOUD, SessionKind.of("newer"))
        assertEquals(SessionKind.LOCAL, SessionKind.of("local"))
    }

    @Test
    fun aRemoteSessionSaysWhatItsDeviceDoesAndResumesOnlyFromIt() {
        val laptop = SessionHost("Ada's MacBook", "macos", "15.1", "app", online = false, removed = false, permissionMode = SessionHost.ASK)
        val offline = cloudSession(status = CloudSessionStatus.PAUSED, desired = "paused", remote = true, host = laptop,
            failure = CloudFailure(CloudSession.HOST_OFFLINE, "m", null))
        assertTrue(offline.remote && offline.hostOffline && !offline.hostRefused && !offline.waitingForHost)
        assertFalse(offline.canResume, "a remote session resumes from its device, which a message asks")
        assertTrue(cloudSession(remote = true).canPause)
        val waiting = cloudSession(status = CloudSessionStatus.CREATING, phase = CloudSession.WAITING_HOST, remote = true, host = laptop)
        assertTrue(waiting.waitingForHost)
        assertFalse(waiting.copy(status = CloudSessionStatus.PAUSED).waitingForHost)
        assertFalse(cloudSession(status = CloudSessionStatus.CREATING, phase = CloudSession.WAITING_HOST).waitingForHost, "a cloud session waits for no device")
        val refused = cloudSession(status = CloudSessionStatus.FAILED, remote = true, failure = CloudFailure(CloudSession.HOST_REFUSED, "m", null))
        assertTrue(refused.hostRefused)
        assertFalse(cloudSession(failure = CloudFailure(CloudSession.HOST_REFUSED, "m", null)).hostRefused)
    }

    @Test
    fun aLocalSessionIsNeverPausedResumedMessagedNorItsModelChangedButItStops() {
        val local = cloudSession(kind = SessionKind.LOCAL)
        assertTrue(local.local && !local.remote)
        assertFalse(local.canPause)
        assertFalse(local.copy(status = CloudSessionStatus.PAUSED, desiredStatus = "paused").canResume)
        assertTrue(local.canStop)
        assertFalse(ControlConversationUseCase.canMessage(local))
        assertFalse(ControlConversationUseCase.canChangeModel(local))
        assertTrue(ControlConversationUseCase.canMessage(cloudSession(remote = true)))
    }

    @Test
    fun theFormStartsOnDevicesAndContinuesConversationsOnlyWhereTheServerSaysSo() = runTest {
        features.features = setOf(Features.RUNNERS_CLOUD, Features.RUNNERS_CLOUD_REMOTE_RESUME)
        val older = LoadCloudSessionOptionsUseCase(access)("a1")
        assertFalse(older.onDevices); assertFalse(older.resumes)
        features.features = setOf(Features.RUNNERS_CLOUD, Features.RUNNERS_CLOUD_REMOTE_DAEMON, Features.RUNNERS_CLOUD_REMOTE_RESUME)
        val options = LoadCloudSessionOptionsUseCase(access)("a1")
        assertTrue(options.onDevices); assertTrue(options.resumes)
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
        CreateCloudSessionUseCase(access)("a1", form, "k1")
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
        CreateCloudSessionUseCase(access)("a1", form, "k1")
        val sent = sessions.created!!
        assertNull(sent.repo, "A recipe replaces the single repository")
        assertEquals(
            CloudRecipe(listOf(CloudRepository("https://github.com/acme/api.git", "api", null, "fix")), null, listOf("npm ci"), listOf("make dev"),
                listOf("NPM_TOKEN", "API_KEY"), true, "acme.dev"),
            sent.recipe,
        )
        // An empty recipe falls back to the single repository.
        CreateCloudSessionUseCase(access)("a1", NewCloudSession(CloudAgent.SHELL, repo = "https://github.com/acme/api", recipe = CloudRecipe(listOf(CloudRepository("")))), "k1")
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
        assertEquals(CloudSessionProblem.TOO_MANY, problem(NewCloudSession(CloudAgent.CLAUDE, recipe = CloudRecipe(setupCreate = List(17) { "echo $it" }))))
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
        assertTrue(cloudSession(status = CloudSessionStatus.PAUSING, desired = "paused").canResume, "a pause under way may be resumed")
        assertFalse(cloudSession(status = CloudSessionStatus.PAUSED, desired = "terminated").canResume, "a stop already asked")
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

    @Test
    fun theGitHubAccessIsChosenOnlyOnAServerThatAnnouncesIt() = runTest {
        val set = SetCloudSessionGitHubAccessUseCase(access)
        val current = cloudSession().copy(githubAccess = GitHubAccess.OWNER)
        features.features = setOf(Features.RUNNERS_CLOUD)
        assertEquals(Features.RUNNERS_CLOUD_GITHUB, assertFailsWith<AppException.Unsupported> { set("a1", current, GitHubAccess.SHARED) }.feature)
        assertFalse(LoadCloudSessionOptionsUseCase(access)("a1").githubAccess)
        assertTrue(sessions.calls.isEmpty())
        features.features = setOf(Features.RUNNERS_CLOUD, Features.RUNNERS_CLOUD_GITHUB)
        assertTrue(LoadCloudSessionOptionsUseCase(access)("a1").githubAccess)
        assertEquals(GitHubAccess.SHARED, set("a1", current, GitHubAccess.SHARED).githubAccess)
        assertEquals(listOf("github c1 shared"), sessions.calls)
    }

    @Test
    fun anEndedSessionSendsNothing() = runTest {
        features.features = setOf(Features.RUNNERS_CLOUD, Features.RUNNERS_CLOUD_GITHUB)
        val set = SetCloudSessionGitHubAccessUseCase(access)
        val refusal = assertFailsWith<AppException.CloudSessionRefused> { set("a1", cloudSession(status = CloudSessionStatus.TERMINATED), GitHubAccess.NONE) }
        assertEquals(CloudSessionRefusal.STATE, refusal.reason)
        assertTrue(sessions.calls.isEmpty())
    }

    @Test
    fun aConversationGoesOnlyToAServerThatRunsOneForAnAgentItRunsHeadless() = runTest {
        val talk = NewCloudSession(CloudAgent.CLAUDE, mode = CloudSessionMode.CONVERSATION)
        assertEquals(CloudSessionMode.TERMINAL, checkNewCloudSession(talk, recipeServed = false).mode)
        assertEquals(CloudSessionMode.CONVERSATION, checkNewCloudSession(talk, recipeServed = false, conversationServed = true).mode)
        assertEquals(CloudSessionMode.TERMINAL, checkNewCloudSession(talk.copy(agent = CloudAgent.SHELL), recipeServed = false, conversationServed = true).mode)
        features.features = setOf(Features.RUNNERS_CLOUD, Features.RUNNERS_CLOUD_CONVERSATION)
        assertTrue(LoadCloudSessionOptionsUseCase(access)("a1").conversation)
        CreateCloudSessionUseCase(access)("a1", talk.copy(agent = CloudAgent.CODEX), "k1")
        assertEquals(CloudSessionMode.CONVERSATION, sessions.created?.mode)
    }
}
