// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import run.ostrom.app.FakeFeatures
import run.ostrom.app.domain.cloudSession
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.domain.models.CloudSessionPage
import run.ostrom.app.domain.models.CloudSessionRefusal
import run.ostrom.app.domain.models.CloudSessionStatus
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.GitHubAccess
import run.ostrom.app.domain.models.NewCloudSession
import run.ostrom.app.domain.repositories.CloudSessionRepository
import run.ostrom.app.domain.usecases.sessions.CloudSessionAccess
import run.ostrom.app.domain.usecases.sessions.ControlCloudSessionUseCase
import run.ostrom.app.domain.usecases.sessions.GetCloudSessionUseCase
import run.ostrom.app.domain.usecases.sessions.LoadCloudSessionOptionsUseCase
import run.ostrom.app.domain.usecases.sessions.SetCloudSessionGitHubAccessUseCase
import run.ostrom.app.fakeGuard
import run.ostrom.app.presentation.navigation.CloudSessionRoute
import run.ostrom.app.presentation.viewmodels.CloudSessionViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The choice of a cloud session's GitHub access on its record: offered with `runners.cloud.github.v1`, refused by key. */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudSessionGitHubAccessTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class Fake(var session: CloudSession) : CloudSessionRepository {
        val calls = mutableListOf<String>()
        var refusal: CloudSessionRefusal? = null
        override suspend fun sessions(accountId: String, before: String?) = CloudSessionPage(listOf(session), null)
        override suspend fun session(accountId: String, sessionId: String) = session
        override suspend fun create(accountId: String, session: NewCloudSession, idempotencyKey: String) = session.let { this.session }
        override suspend fun pause(accountId: String, sessionId: String) = session
        override suspend fun resume(accountId: String, sessionId: String) = session
        override suspend fun stop(accountId: String, sessionId: String) = session
        override suspend fun setGitHubAccess(accountId: String, sessionId: String, access: GitHubAccess): CloudSession {
            calls += "github ${access.wire}"
            refusal?.let { throw AppException.CloudSessionRefused(it) }
            session = session.copy(githubAccess = access)
            return session
        }
    }

    private val features = FakeFeatures(setOf(Features.RUNNERS_CLOUD, Features.RUNNERS_CLOUD_GITHUB))

    private fun model(repository: Fake): CloudSessionViewModel {
        val access = CloudSessionAccess(repository, features, fakeGuard())
        return CloudSessionViewModel(
            CloudSessionRoute("a1", "c1"), GetCloudSessionUseCase(access), ControlCloudSessionUseCase(access), LoadCloudSessionOptionsUseCase(access),
            SetCloudSessionGitHubAccessUseCase(access),
        )
    }

    private fun running(access: GitHubAccess? = GitHubAccess.OWNER) = cloudSession().copy(githubAccess = access)

    @Test
    fun theChoiceIsOfferedOnARunningSessionOfAServerThatAnnouncesIt() = runTest(dispatcher) {
        val model = model(Fake(running()))
        model.load(); runCurrent()
        assertTrue(model.uiState.value.canChooseAccess)
        features.features = setOf(Features.RUNNERS_CLOUD)
        model.load(); runCurrent()
        assertFalse(model.uiState.value.canChooseAccess, "no announcement, no choice")
    }

    @Test
    fun noChoiceWithoutAnAccessTheServerSaysOrOnAnEndedSession() = runTest(dispatcher) {
        val unknown = model(Fake(running(access = null)))
        unknown.load(); runCurrent()
        assertFalse(unknown.uiState.value.canChooseAccess)
        val ended = model(Fake(cloudSession(status = CloudSessionStatus.TERMINATED).copy(githubAccess = GitHubAccess.OWNER)))
        ended.load(); runCurrent()
        assertFalse(ended.uiState.value.canChooseAccess)
    }

    @Test
    fun aChoiceIsSentAndTheSessionShowsIt() = runTest(dispatcher) {
        val repository = Fake(running())
        val model = model(repository)
        model.load(); runCurrent()
        model.chooseGitHubAccess(GitHubAccess.SHARED); runCurrent()
        assertEquals(listOf("github shared"), repository.calls)
        assertEquals(GitHubAccess.SHARED, model.uiState.value.session?.githubAccess)
        assertNull(model.uiState.value.settingAccess)
        assertNull(model.uiState.value.actionError)
        model.chooseGitHubAccess(GitHubAccess.SHARED); runCurrent()
        assertEquals(1, repository.calls.size, "the access it already has is not sent again")
    }

    @Test
    fun takingTheAccessAwayFromARunningSessionIsRefusedWithAPlainTranslatedSentence() = runTest(dispatcher) {
        val repository = Fake(running()).also { it.refusal = CloudSessionRefusal.GITHUB_NOT_PAUSED }
        val model = model(repository)
        model.load(); runCurrent()
        model.chooseGitHubAccess(GitHubAccess.NONE); runCurrent()
        val error = assertNotNull(model.uiState.value.actionError)
        assertEquals("Pause the session before removing its GitHub access.", error.text)
        assertNotNull(error.resource)
        assertEquals(GitHubAccess.OWNER, model.uiState.value.session?.githubAccess)
        assertNull(model.uiState.value.settingAccess)
    }
}
