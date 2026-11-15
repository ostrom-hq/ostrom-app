// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import run.ostrom.app.ConversationCalls
import run.ostrom.app.FakeFeatures
import run.ostrom.app.SequenceIds
import run.ostrom.app.domain.cloudSession
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.domain.models.CloudSessionPage
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.CloudAgent
import run.ostrom.app.domain.models.CloudSessionMode
import run.ostrom.app.domain.models.GitHubAccess
import run.ostrom.app.domain.models.NewCloudSession
import run.ostrom.app.domain.usecases.sessions.CloudSessionAccess
import run.ostrom.app.domain.usecases.sessions.CreateCloudSessionUseCase
import run.ostrom.app.domain.usecases.sessions.LoadCloudSessionOptionsUseCase
import run.ostrom.app.fakeGuard
import run.ostrom.app.presentation.navigation.NewCloudSessionRoute
import run.ostrom.app.presentation.viewmodels.CloudField
import run.ostrom.app.presentation.viewmodels.NewCloudSessionViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/** The new session form keeps one idempotency key per form pressed, so Start repeated after a lost answer is the same press. */
@OptIn(ExperimentalCoroutinesApi::class)
class NewCloudSessionViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Creates that fail while [failures] are left, with a lost connection. */
    private class Creates(var failures: Int) : ConversationCalls() {
        override fun current(sessionId: String) = cloudSession(sessionId)
        val keys = mutableListOf<String>()
        var created: NewCloudSession? = null
        override suspend fun sessions(accountId: String, before: String?) = CloudSessionPage(emptyList(), null)
        override suspend fun session(accountId: String, sessionId: String): CloudSession = cloudSession(sessionId)
        override suspend fun create(accountId: String, session: NewCloudSession, idempotencyKey: String): CloudSession {
            keys += idempotencyKey
            created = session
            if (failures-- > 0) throw AppException.Unreachable()
            return cloudSession("new")
        }
        override suspend fun pause(accountId: String, sessionId: String): CloudSession = cloudSession(sessionId)
        override suspend fun resume(accountId: String, sessionId: String): CloudSession = cloudSession(sessionId)
        override suspend fun stop(accountId: String, sessionId: String): CloudSession = cloudSession(sessionId)
        override suspend fun setGitHubAccess(accountId: String, sessionId: String, access: GitHubAccess): CloudSession = cloudSession(sessionId)
    }

    private fun model(creates: Creates, vararg features: String = arrayOf(Features.RUNNERS_CLOUD)): NewCloudSessionViewModel {
        val access = CloudSessionAccess(creates, FakeFeatures(features.toSet()), fakeGuard())
        return NewCloudSessionViewModel(
            NewCloudSessionRoute("a1"),
            LoadCloudSessionOptionsUseCase(access),
            CreateCloudSessionUseCase(access),
            SequenceIds(),
        )
    }

    private fun TestScope.settle() = runCurrent()

    @Test fun aCreateRepeatedAfterALostAnswerCarriesTheSameKeyAndAnotherFormAnother() = runTest(dispatcher) {
        val creates = Creates(failures = 2)
        val model = model(creates); settle()
        model.onField(CloudField.NAME, "Mill"); settle()
        model.create(); settle()
        assertNotNull(model.uiState.value.createError)
        model.create(); settle()
        assertEquals(2, creates.keys.size)
        assertEquals(creates.keys[0], creates.keys[1], "The same press, so the server returns the first session")
        model.onField(CloudField.NAME, "Mill 2"); settle()
        model.create(); settle()
        assertEquals(3, creates.keys.size)
        assertEquals(2, creates.keys.distinct().size, "Another form is another press, so another key")
        assertNull(model.uiState.value.createError)
        assertEquals("new", model.uiState.value.created?.id)
    }

    @Test
    fun aConversationIsOfferedForAnAgentTheServerRunsHeadless() = runTest(dispatcher) {
        val older = model(Creates(failures = 0)); settle()
        assertFalse(older.uiState.value.conversationOffered, "a server without conversations offers none")
        val creates = Creates(failures = 0)
        val model = model(creates, Features.RUNNERS_CLOUD, Features.RUNNERS_CLOUD_CONVERSATION); settle()
        assertTrue(model.uiState.value.conversationOffered)
        model.onConversation(true)
        model.onAgent(CloudAgent.GEMINI)
        assertFalse(model.uiState.value.conversationOffered)
        assertEquals(CloudSessionMode.TERMINAL, model.uiState.value.form().mode, "an agent without a headless mode starts in a terminal")
        model.onAgent(CloudAgent.CODEX)
        model.create(); settle()
        assertEquals(CloudSessionMode.CONVERSATION, creates.created?.mode)
    }
}
