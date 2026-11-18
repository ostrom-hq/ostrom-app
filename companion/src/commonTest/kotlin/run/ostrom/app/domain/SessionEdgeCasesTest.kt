// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.domain

import kotlinx.coroutines.test.runTest
import run.ostrom.app.FakeCloudSessionRepository
import run.ostrom.app.FakeFeatures
import run.ostrom.app.SequenceIds
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.CloudAgent
import run.ostrom.app.domain.models.CloudRecipe
import run.ostrom.app.domain.models.CloudRepository
import run.ostrom.app.domain.models.CloudSessionProblem
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.InputReceipt
import run.ostrom.app.domain.models.NewCloudSession
import run.ostrom.app.domain.models.PendingMessage
import run.ostrom.app.domain.models.TranscriptEvent
import run.ostrom.app.domain.usecases.sessions.ControlConversationUseCase
import run.ostrom.app.domain.usecases.sessions.MessageRules
import run.ostrom.app.domain.usecases.sessions.SendMessageUseCase
import run.ostrom.app.domain.usecases.sessions.checkNewCloudSession
import run.ostrom.app.domain.usecases.sessions.reconciled
import run.ostrom.app.fakeGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionEdgeCasesTest {
    private val sessions = FakeCloudSessionRepository(cloudSession("s1"))
    private val features = FakeFeatures(setOf(Features.RUNNERS_CLOUD, Features.RUNNERS_CLOUD_CONVERSATION, Features.RUNNERS_CLOUD_TASKS))
    private val send = SendMessageUseCase(sessions, features, fakeGuard(), SequenceIds())
    private val control = ControlConversationUseCase(sessions, fakeGuard(), features)

    @Test
    fun aCarriageReturnIsTextAndOtherControlCharactersAreNot() {
        assertTrue(MessageRules.valid("one\r\ntwo"))
        assertFalse(MessageRules.valid("esc\u001B"))
    }

    @Test
    fun aTimeoutMayPassAndACancelledReceiptIsFinal() = runTest {
        sessions.sendResult = { throw AppException.Server(408, null) }
        val timedOut = send("a1", "s1", send.draft("hello"))
        assertEquals(PendingMessage.State.FAILED, timedOut.state)
        assertEquals(AppException.Server(408, null).message, timedOut.reason)

        sessions.sendResult = { InputReceipt("in-9", "cancelled", woke = false, reason = null) }
        val cancelled = send("a1", "s1", send.draft("hello"))
        assertEquals(PendingMessage.State.REFUSED, cancelled.state)
        assertEquals("in-9", cancelled.inputId)
    }

    @Test
    fun aRefusalWithoutAReasonIsNamedByItsStatus() {
        val pending = listOf(PendingMessage("c1", "hi", PendingMessage.State.SENT, inputId = "in-1"), PendingMessage("c2", "yo", PendingMessage.State.SENT, inputId = "in-2"))
        val events = listOf(
            TranscriptEvent.InputChanged(1, 1, "in-1", "cancelled", null),
            TranscriptEvent.InputChanged(2, 2, "in-2", "rejected", "too_large"),
            TranscriptEvent.InputChanged(3, 3, "in-3", "delivered", null),
        )
        val reconciled = pending.reconciled(events)
        assertEquals(listOf("cancelled", "too_large"), reconciled.map { it.reason })
        assertTrue(reconciled.all { it.state == PendingMessage.State.REFUSED })
    }

    @Test
    fun anInterruptIsRefusedUnlessThereIsNothingToInterrupt() = runTest {
        sessions.interruptResult = { throw AppException.Server(409, "session_not_running") }
        assertEquals("session_not_running", assertFailsWith<AppException.Server> { control.interrupt("a1", "s1") }.code)
        sessions.interruptResult = { throw AppException.Server(500, "nothing_to_interrupt") }
        assertEquals(500, assertFailsWith<AppException.Server> { control.interrupt("a1", "s1") }.statusCode)
    }

    @Test
    fun stoppingATaskWithNoAgentRunningIsNotAFailure() = runTest {
        sessions.stopTaskResult = { throw AppException.Server(409, "nothing_to_stop") }
        assertFalse(control.stopTask("a1", "s1", "t1"))
        sessions.stopTaskResult = { throw AppException.Server(409, "nothing_to_interrupt") }
        assertFalse(control.stopTask("a1", "s1", "t1"))
        sessions.stopTaskResult = { throw AppException.Server(409, "task_not_found") }
        assertEquals("task_not_found", assertFailsWith<AppException.Server> { control.stopTask("a1", "s1", "t1") }.code)
        sessions.stopTaskResult = { throw AppException.Server(400, "nothing_to_stop") }
        assertEquals(400, assertFailsWith<AppException.Server> { control.stopTask("a1", "s1", "t1") }.statusCode)
    }

    // --- A new cloud session's form ---

    private fun problem(form: NewCloudSession, recipe: Boolean = true) = assertFailsWith<AppException.CloudSessionInvalid> { checkNewCloudSession(form, recipe) }.problem

    @Test
    fun aRecipeTheServerDoesNotServeFallsBackToTheRepository() {
        val form = NewCloudSession(CloudAgent.CLAUDE, repo = " https://github.com/acme/api ", ref = "  ", recipe = CloudRecipe(setupCreate = listOf("make")))
        val checked = checkNewCloudSession(form, recipeServed = false)
        assertNull(checked.recipe)
        assertEquals("https://github.com/acme/api", checked.repo)
        assertNull(checked.ref)
    }

    @Test
    fun aRecipeLeftEmptyIsNoRecipe() {
        val blank = CloudRecipe(repositories = listOf(CloudRepository("  ", path = " ", ref = "", branch = null)), workingDirectory = " ", setupCreate = listOf(" "))
        assertEquals(NewCloudSession(CloudAgent.CLAUDE), checkNewCloudSession(NewCloudSession(CloudAgent.CLAUDE, model = " ", recipe = blank), recipeServed = true))
    }

    @Test
    fun aRowWithOnlyARefOrABranchIsKeptAndRefused() {
        assertEquals(CloudSessionProblem.REPOSITORY, problem(NewCloudSession(CloudAgent.CLAUDE, recipe = CloudRecipe(repositories = listOf(CloudRepository("", ref = "v1"))))))
        assertEquals(CloudSessionProblem.REPOSITORY, problem(NewCloudSession(CloudAgent.CLAUDE, recipe = CloudRecipe(repositories = listOf(CloudRepository("", branch = "dev"))))))
    }

    @Test
    fun tooManyStartCommandsOrAnOverlongCommandAreRefused() {
        assertEquals(CloudSessionProblem.TOO_MANY, problem(NewCloudSession(CloudAgent.CLAUDE, recipe = CloudRecipe(setupStart = List(17) { "echo $it" }))))
        assertEquals(CloudSessionProblem.TOO_MANY, problem(NewCloudSession(CloudAgent.CLAUDE, recipe = CloudRecipe(setupStart = listOf("x".repeat(4097))))))
    }
}
