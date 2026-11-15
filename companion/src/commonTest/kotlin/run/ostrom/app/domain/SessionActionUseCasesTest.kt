// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../domain/SessionActionUseCasesTest.kt; see NOTICE.
package run.ostrom.app.domain

import kotlinx.coroutines.test.runTest
import run.ostrom.app.FakeCloudSessionRepository
import run.ostrom.app.FakeFeatures
import run.ostrom.app.SequenceIds
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.CloudSessionProblem
import run.ostrom.app.domain.models.CloudSessionStatus
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.InputReceipt
import run.ostrom.app.domain.models.PendingMessage
import run.ostrom.app.domain.models.TranscriptEvent
import run.ostrom.app.domain.usecases.sessions.ConversationOptions
import run.ostrom.app.domain.usecases.sessions.ControlConversationUseCase
import run.ostrom.app.domain.usecases.sessions.SendMessageUseCase
import run.ostrom.app.domain.usecases.sessions.reconciled
import run.ostrom.app.fakeGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionActionUseCasesTest {
    private val sessions = FakeCloudSessionRepository(cloudSession("s1"))
    private val features = FakeFeatures(setOf(Features.RUNNERS_CLOUD, Features.RUNNERS_CLOUD_CONVERSATION))
    private val send = SendMessageUseCase(sessions, features, fakeGuard(), SequenceIds())
    private val control = ControlConversationUseCase(sessions, fakeGuard(), features)

    @Test
    fun aFailedMessageIsRetriedWithTheSameIdAndARefusalIsFinal() = runTest {
        val draft = send.draft("Fix the test")
        assertEquals(PendingMessage.State.SENDING, draft.state)
        sessions.sendResult = { throw AppException.Unreachable() }
        val failed = send("a1", "s1", draft)
        assertEquals(PendingMessage.State.FAILED, failed.state)
        sessions.sendResult = { InputReceipt("in-1", "queued", woke = true, reason = null) }
        val sent = send("a1", "s1", failed)
        assertEquals(PendingMessage.State.SENT, sent.state)
        assertEquals("in-1", sent.inputId)
        assertTrue(sent.woke)
        assertEquals(2, sessions.calls.count { it == "send s1 Fix the test ${draft.clientMessageId}" })
        sessions.sendResult = { throw AppException.RateLimited(30) }
        assertEquals(PendingMessage.State.FAILED, send("a1", "s1", send.draft("again")).state)
        sessions.sendResult = { throw AppException.Server(503, null) }
        assertEquals(PendingMessage.State.FAILED, send("a1", "s1", send.draft("again")).state)
        sessions.sendResult = { throw AppException.Server(409, "session_not_running") }
        val refused = send("a1", "s1", send.draft("late"))
        assertEquals(PendingMessage.State.REFUSED, refused.state)
        assertEquals("session_not_running", refused.reason)
        sessions.sendResult = { throw AppException.NotFound() }
        assertEquals(PendingMessage.State.REFUSED, send("a1", "s1", send.draft("gone")).state)
        // A replayed acknowledgement of a message that was then refused is final.
        sessions.sendResult = { InputReceipt("in-2", "rejected", woke = false, reason = "delivery_failed") }
        val rejected = send("a1", "s1", send.draft("lost"))
        assertEquals(PendingMessage.State.REFUSED, rejected.state)
        assertEquals("delivery_failed", rejected.reason)
    }

    @Test
    fun invalidMessagesAreRefusedBeforeAnyRequestAndAServerWithoutMessagesSaysSo() = runTest {
        for (text in listOf("", "   ", "a".repeat(32_001), "bell\u0007")) {
            assertEquals(CloudSessionProblem.MESSAGE, assertFailsWith<AppException.CloudSessionInvalid>(text) { send.draft(text) }.problem)
        }
        send.draft("line one\nline two\ttab")
        send.draft("a".repeat(32_000))
        features.features = setOf(Features.RUNNERS_CLOUD)
        assertEquals(PendingMessage.State.REFUSED, send("a1", "s1", send.draft("hello")).state)
        assertTrue(sessions.calls.none { it.startsWith("send") })
    }

    @Test
    fun theTranscriptReplacesAPendingMessageAndAnInputEventRefusesIt() {
        val pending = listOf(
            PendingMessage("c1", "one", PendingMessage.State.SENT, inputId = "i1"),
            PendingMessage("c2", "two", PendingMessage.State.SENT, inputId = "i2"),
            PendingMessage("c3", "three", PendingMessage.State.SENDING),
            PendingMessage("c4", "four", PendingMessage.State.SENT, inputId = "i4"),
        )
        val events = listOf(TranscriptEvent.UserMessage(1, 1, "one", "i1", "m1"), TranscriptEvent.InputChanged(2, 2, "i2", "rejected", "delivery_failed"))
        val left = pending.reconciled(events)
        assertEquals(listOf("c2", "c3", "c4"), left.map { it.clientMessageId })
        assertEquals(PendingMessage.State.REFUSED, left[0].state)
        assertEquals("delivery_failed", left[0].reason)
        assertEquals(PendingMessage.State.SENT, left[2].state)
        assertEquals(emptyList(), emptyList<PendingMessage>().reconciled(events))
    }

    @Test
    fun aConsumedInputSettlesAMessageRecordedOnlyAsASizeMarker() {
        val pending = listOf(PendingMessage("c1", "long", PendingMessage.State.SENT, inputId = "i1"))
        val events = listOf(TranscriptEvent.Truncated(1, 1, "message.user", 70_000), TranscriptEvent.InputChanged(2, 2, "i1", "consumed", null))
        assertTrue(pending.reconciled(events).isEmpty())
    }

    @Test
    fun theOptionsAreWhatTheServerAnnounces() = runTest {
        assertEquals(ConversationOptions(conversation = true), control.options("a1"))
        features.features = setOf(
            Features.RUNNERS_CLOUD_CONVERSATION, Features.RUNNERS_CLOUD_PERMISSIONS, Features.RUNNERS_CLOUD_PERMISSIONS_BYPASS,
            Features.RUNNERS_CLOUD_MODEL, Features.RUNNERS_CLOUD_EFFORT, Features.RUNNERS_CLOUD_TASKS, Features.RUNNERS_CLOUD_TRANSCRIPT_FEEDS,
        )
        assertEquals(ConversationOptions(true, true, true, true, true, true, true), control.options("a1"))
    }

    @Test
    fun nothingToInterruptIsNotAFailureAndControlsFollowTheOwner() = runTest {
        assertTrue(control.interrupt("a1", "s1"))
        assertEquals("interrupt s1", sessions.calls.last())
        sessions.interruptResult = { throw AppException.Server(409, "nothing_to_interrupt") }
        assertFalse(control.interrupt("a1", "s1"))
        sessions.interruptResult = { throw AppException.Server(409, "session_not_running") }
        assertFailsWith<AppException.Server> { control.interrupt("a1", "s1") }
        features.features = setOf(Features.RUNNERS_CLOUD)
        val asked = sessions.calls.size
        assertFailsWith<AppException.Unsupported> { control.interrupt("a1", "s1") }
        assertEquals(asked, sessions.calls.size)
        val mine = cloudSession("s", ownerId = "m1")
        val theirs = cloudSession("s", ownerId = "m2")
        assertTrue(ControlConversationUseCase.canControl("m1", mine))
        assertFalse(ControlConversationUseCase.canControl("m1", theirs))
        assertFalse(ControlConversationUseCase.canControl(null, cloudSession("s")), "A session without an owner, or an account without a member id, is nobody's")
        assertTrue(ControlConversationUseCase.canMessage(cloudSession("p", CloudSessionStatus.PAUSED)))
        assertFalse(ControlConversationUseCase.canMessage(cloudSession("t", CloudSessionStatus.TERMINATED)))
        assertFalse(ControlConversationUseCase.canMessage(cloudSession("x", CloudSessionStatus.TRANSFERRING)))
    }

    @Test
    fun aTaskStopsWhereTheServerTakesItAndNothingRunningIsNotAFailure() = runTest {
        assertFailsWith<AppException.Unsupported> { control.stopTask("a1", "s1", "b1") }
        features.features += Features.RUNNERS_CLOUD_TASKS
        assertTrue(control.stopTask("a1", "s1", "b1"))
        assertEquals("stopTask s1 b1", sessions.calls.last())
        for (code in listOf("nothing_to_interrupt", "nothing_to_stop")) {
            sessions.stopTaskResult = { throw AppException.Server(409, code) }
            assertFalse(control.stopTask("a1", "s1", "b1"))
        }
        sessions.stopTaskResult = { throw AppException.Server(400, "task_stop_unsupported") }
        assertFailsWith<AppException.Server> { control.stopTask("a1", "s1", "b1") }
    }

    @Test
    fun bypassIsTheOwnersAndOnlyWhereTheServerTakesIt() = runTest {
        assertFailsWith<AppException.Unsupported> { control.setPermissionBypass("a1", "s1", true) }
        features.features += Features.RUNNERS_CLOUD_PERMISSIONS_BYPASS
        assertTrue(control.setPermissionBypass("a1", "s1", true).permissionBypass)
        assertFalse(control.setPermissionBypass("a1", "s1", false).permissionBypass)
        assertEquals(listOf("bypass s1 true", "bypass s1 false"), sessions.calls)
    }

    @Test
    fun theEffortChangesOnlyWhereTheServerTakesItWithTheServersRules() = runTest {
        assertFailsWith<AppException.Unsupported> { control.setEffort("a1", "s1", "max") }
        features.features += Features.RUNNERS_CLOUD_EFFORT
        assertEquals("max", control.setEffort("a1", "s1", "  max  ").effort)
        assertTrue(sessions.calls.contains("setEffort s1 max"))
        for (bad in listOf("", "   ", "two words", "x".repeat(121), "semi;colon", "line\nbreak")) {
            assertEquals(CloudSessionProblem.EFFORT, assertFailsWith<AppException.CloudSessionInvalid>(bad) { control.setEffort("a1", "s1", bad) }.problem)
        }
        assertEquals(1, sessions.calls.count { it.startsWith("setEffort") })
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), ControlConversationUseCase.effortLevels("claude"))
        assertEquals(listOf("low", "medium", "high", "xhigh"), ControlConversationUseCase.effortLevels("codex"))
    }

    @Test
    fun theModelChangesOnlyWhereTheServerTakesItWithTheServersIdRules() = runTest {
        assertFailsWith<AppException.Unsupported> { control.setModel("a1", "s1", "claude-sonnet-5-5") }
        features.features += Features.RUNNERS_CLOUD_MODEL
        assertEquals("claude-sonnet-5-5", control.setModel("a1", "s1", "  claude-sonnet-5-5  ").model)
        assertTrue(sessions.calls.contains("setModel s1 claude-sonnet-5-5"))
        for (bad in listOf("", "   ", "two words", "x".repeat(121), "semi;colon", "line\nbreak")) {
            assertEquals(CloudSessionProblem.MODEL, assertFailsWith<AppException.CloudSessionInvalid>(bad) { control.setModel("a1", "s1", bad) }.problem)
        }
        assertEquals(1, sessions.calls.count { it.startsWith("setModel") })
        assertTrue(ControlConversationUseCase.validModel("x".repeat(120)) && ControlConversationUseCase.validModel("gpt-6-sol") && ControlConversationUseCase.validModel("a.b_c:d/e-f"))
        // Offered for any session that has not ended.
        assertTrue(ControlConversationUseCase.canChangeModel(cloudSession("p", CloudSessionStatus.PAUSED)))
        assertFalse(ControlConversationUseCase.canChangeModel(cloudSession("e", CloudSessionStatus.TERMINATED)))
    }
}
