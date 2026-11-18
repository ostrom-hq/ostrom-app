// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.domain

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import run.ostrom.app.FakeCloudSessionRepository
import run.ostrom.app.FakeFeatures
import run.ostrom.app.SequenceIds
import run.ostrom.app.domain.models.AgentQuestion
import run.ostrom.app.domain.models.AgentQuestionOption
import run.ostrom.app.domain.models.AnswerOutcome
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.CloudSessionProblem
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.InputReceipt
import run.ostrom.app.domain.models.NewDeviceSession
import run.ostrom.app.domain.models.PermissionAnswer
import run.ostrom.app.domain.models.PermissionMode
import run.ostrom.app.domain.models.QuestionAnswer
import run.ostrom.app.domain.models.QuestionChoice
import run.ostrom.app.domain.models.TranscriptEvent
import run.ostrom.app.domain.usecases.sessions.AnswerPermissionUseCase
import run.ostrom.app.domain.usecases.sessions.CloudSessionAccess
import run.ostrom.app.domain.usecases.sessions.CreateDeviceSessionUseCase
import run.ostrom.app.domain.usecases.sessions.PermissionRules
import run.ostrom.app.domain.usecases.sessions.openPermissionRequests
import run.ostrom.app.fakeGuard
import run.ostrom.app.infrastructure.settings.SettingsPreferencesRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PermissionAndDeviceEdgeCasesTest {
    private val sessions = FakeCloudSessionRepository(cloudSession("s1"))

    // --- Answers to an agent's questions ---

    private val question = AgentQuestion(0, "Which?", null, listOf(AgentQuestionOption("Red", null), AgentQuestionOption("Blue", null)), multiSelect = true)

    @Test
    fun freeTextIsRefusedWhenBlankTooLongOrWithControlCharacters() {
        assertTrue(PermissionRules.validText("one\r\ntwo\tthree"))
        assertFalse(PermissionRules.validText("   "))
        assertFalse(PermissionRules.validText("x".repeat(4_001)))
        assertFalse(PermissionRules.validText("bell\u0007"))
        assertNull(PermissionRules.answer(question, QuestionChoice(other = true, otherText = "  ")))
    }

    @Test
    fun optionsTheQuestionDoesNotHaveAreIgnored() {
        assertEquals(QuestionAnswer(0, options = listOf(0, 1)), PermissionRules.answer(question, QuestionChoice(options = setOf(1, 7, 0, -1))))
        assertNull(PermissionRules.answer(question, QuestionChoice(options = setOf(5))))
    }

    @Test
    fun otherWithOptionsTooLongOnceJoinedAnswersNothing() {
        val other = "x".repeat(3_996)
        assertNull(PermissionRules.answer(question, QuestionChoice(options = setOf(0), other = true, otherText = other)))
        assertEquals(QuestionAnswer(0, text = "Red, ${"x".repeat(3_990)}"), PermissionRules.answer(question, QuestionChoice(options = setOf(0), other = true, otherText = "x".repeat(3_990))))
    }

    @Test
    fun anInputWithoutARequestOrNotConsumedLeavesTheRequestsOpen() {
        val events = listOf(
            TranscriptEvent.PermissionRequest(1, 1, "a", "Bash", false, input = "{}"),
            TranscriptEvent.PermissionRequest(2, 2, null, "Bash", false, input = "{}"),
            TranscriptEvent.InputChanged(3, 3, "i1", "consumed", null, requestId = null),
            TranscriptEvent.InputChanged(4, 4, "i2", "delivered", null, requestId = "a"),
            TranscriptEvent.PermissionExpired(5, 5, null),
        )
        assertEquals(listOf("a"), events.openPermissionRequests().map { it.requestId })
    }

    @Test
    fun aCancelledAcknowledgementIsARefusal() = runTest {
        val features = FakeFeatures(setOf(Features.RUNNERS_CLOUD, Features.RUNNERS_CLOUD_CONVERSATION, Features.RUNNERS_CLOUD_PERMISSIONS))
        sessions.answerResult = { InputReceipt("ans", "cancelled", woke = false, reason = "turn_ended") }
        val outcome = AnswerPermissionUseCase(sessions, features, fakeGuard(), SequenceIds())("a1", "s1", PermissionAnswer("r1", allow = true), "k1")
        assertEquals(AnswerOutcome.Refused("turn_ended", null), outcome)
    }

    // --- A session on a device ---

    private val preferences = SettingsPreferencesRepository(MapSettings())
    private val create = CreateDeviceSessionUseCase(
        CloudSessionAccess(sessions, FakeFeatures(setOf(Features.RUNNERS_CLOUD, Features.RUNNERS_CLOUD_REMOTE_DAEMON)), fakeGuard()), preferences,
    )
    private val form = NewDeviceSession("Fix", "d1", "f1", "claude", null, null, PermissionMode.BYPASS, null)

    private suspend fun problem(form: NewDeviceSession) = assertFailsWith<AppException.CloudSessionInvalid> { create("a1", form, "k") }.problem

    @Test
    fun theDaemonRunsClaudeAndCodex() {
        assertEquals(setOf("claude", "codex"), CreateDeviceSessionUseCase.AGENTS)
    }

    @Test
    fun aNameThatIsTooLongOrHoldsControlCharactersIsRefused() = runTest {
        assertEquals(CloudSessionProblem.NAME, problem(form.copy(name = "x".repeat(201))))
        assertEquals(CloudSessionProblem.NAME, problem(form.copy(name = "a\u0007b")))
        assertTrue(sessions.calls.isEmpty())
    }

    @Test
    fun aValidModelIsSent() = runTest {
        create("a1", form.copy(model = "gpt-5.1"), "k")
        assertEquals(listOf("createOnDevice k"), sessions.calls)
    }

    @Test
    fun anInvalidModelIsRefusedBeforeAnyRequest() = runTest {
        assertEquals(CloudSessionProblem.MODEL, problem(form.copy(model = "gpt 5!")))
        assertTrue(sessions.calls.isEmpty())
    }

    @Test
    fun aServerRefusalThatIsNotAboutTheDeviceStaysAsItIs() = runTest {
        sessions.createOnDeviceResult = { throw AppException.Server(400, null) }
        assertNull(assertFailsWith<AppException.Server> { create("a1", form, "k") }.code)
        sessions.createOnDeviceResult = { throw AppException.Server(422, "invalid_model") }
        assertEquals("invalid_model", assertFailsWith<AppException.Server> { create("a1", form, "k") }.code)
    }
}
