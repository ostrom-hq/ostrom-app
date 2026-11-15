// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../presentation/SessionViewModelTest.kt; see NOTICE.
package run.ostrom.app.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import run.ostrom.app.FakeCloudSessionRepository
import run.ostrom.app.FakeFeatures
import run.ostrom.app.conversationViewModel
import run.ostrom.app.domain.cloudSession
import run.ostrom.app.domain.models.AgentQuestion
import run.ostrom.app.domain.models.AgentQuestionOption
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.CloudSessionMode
import run.ostrom.app.domain.models.CloudSessionStatus
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.InputReceipt
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.PendingMessage
import run.ostrom.app.domain.models.PermissionAnswer
import run.ostrom.app.domain.models.QuestionAnswer
import run.ostrom.app.domain.models.QuestionChoice
import run.ostrom.app.domain.models.TranscriptEvent
import run.ostrom.app.domain.models.TranscriptPage
import run.ostrom.app.domain.models.TurnPhase
import run.ostrom.app.domain.repositories.TranscriptRepository
import run.ostrom.app.domain.usecases.sessions.ConversationOptions
import run.ostrom.app.domain.usecases.transcript.Transcript
import run.ostrom.app.presentation.viewmodels.AnswerState
import run.ostrom.app.presentation.viewmodels.CloudSessionUiState
import run.ostrom.app.presentation.viewmodels.CloudSessionViewModel
import run.ostrom.app.presentation.viewmodels.LiveState
import run.ostrom.app.presentation.viewmodels.SessionNotice
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A conversation session's screen (`mode: conversation`): its transcript, composer, interrupt, permissions and tasks. */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudConversationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val live = Channel<LiveUpdate<TranscriptEvent>>(Channel.UNLIMITED)
    private val follows = mutableListOf<Long>()

    /** The newest page the screen loads first. */
    private var firstPage = TranscriptPage(listOf(TranscriptEvent.AgentMessage(4, 4, "Hello", null)), nextBefore = 4, lastSeq = 4)
    private val transcripts = object : TranscriptRepository {
        override suspend fun page(accountId: String, sessionId: String, before: Long?) = firstPage
        override fun follow(accountId: String, sessionId: String, after: Long): Flow<LiveUpdate<TranscriptEvent>> {
            follows += after
            return live.receiveAsFlow()
        }
    }
    private val talk = cloudSession("s1", ownerId = "m1").copy(mode = CloudSessionMode.CONVERSATION)
    private val sessions = FakeCloudSessionRepository(talk)
    private val features = FakeFeatures(
        setOf(
            Features.RUNNERS_CLOUD, Features.RUNNERS_CLOUD_CONVERSATION, Features.RUNNERS_CLOUD_PERMISSIONS,
            Features.RUNNERS_CLOUD_PERMISSIONS_BYPASS, Features.RUNNERS_CLOUD_TASKS, Features.RUNNERS_CLOUD_MODEL, Features.RUNNERS_CLOUD_EFFORT,
        ),
    )

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(memberId: String? = "m1"): CloudSessionViewModel = conversationViewModel(sessions, transcripts, features, memberId)

    /** Runs what is due within a second of virtual time; the header's periodic reads never let the scheduler go idle. */
    private fun TestScope.settle() {
        advanceTimeBy(1_000)
        runCurrent()
    }

    @Test
    fun aTerminalSessionOrAServerWithoutConversationsShowsTheRecordOnly() = runTest(dispatcher) {
        sessions.session = talk.copy(mode = CloudSessionMode.TERMINAL)
        val terminal = viewModel()
        terminal.visible(); settle()
        assertFalse(terminal.uiState.value.conversation)
        assertTrue(follows.isEmpty())
        terminal.hidden()
        sessions.session = talk
        features.features = setOf(Features.RUNNERS_CLOUD)
        val older = viewModel()
        older.visible(); settle()
        assertFalse(older.uiState.value.conversation)
        assertEquals(ConversationOptions(), older.uiState.value.options)
        assertTrue(follows.isEmpty(), "a server without the key is not asked for a transcript")
        older.hidden()
    }

    @Test
    fun theTranscriptLoadsThenFollowsAfterItsNewestEventAndStopsWhenHidden() = runTest(dispatcher) {
        val model = viewModel()
        model.visible(); settle()
        assertTrue(model.uiState.value.conversation)
        assertTrue(model.uiState.value.loaded)
        assertEquals(listOf(4L), follows)
        assertEquals(LiveState.CONNECTING, model.uiState.value.live)
        live.send(LiveUpdate.Opened(false)); settle()
        assertEquals(LiveState.LIVE, model.uiState.value.live)
        live.send(LiveUpdate.Item(TranscriptEvent.AgentMessage(5, 5, "More", null))); settle()
        assertEquals(listOf(4L, 5L), model.uiState.value.transcript.events.map { it.seq })
        model.hidden()
        assertEquals(LiveState.STOPPED, model.uiState.value.live)
    }

    @Test
    fun aRunningSessionWhoseLastTurnStartedIsRunning() {
        val started = Transcript(listOf(TranscriptEvent.Turn(5, 0, TurnPhase.STARTED, null, null)))
        assertTrue(CloudSessionUiState(session = talk, transcript = started).running)
        assertFalse(CloudSessionUiState(session = talk.copy(status = CloudSessionStatus.PAUSED), transcript = started).running)
        val ended = Transcript(listOf(TranscriptEvent.Turn(6, 0, TurnPhase.COMPLETED, 10, 1)))
        assertFalse(CloudSessionUiState(session = talk, transcript = ended).running)
        assertFalse(CloudSessionUiState(session = talk).running)
    }

    @Test
    fun aSentMessageWaitsUntilTheTranscriptRecordsItAndAFailedOneRetriesWithItsId() = runTest(dispatcher) {
        val model = viewModel()
        model.visible(); settle()
        assertTrue(model.uiState.value.canControl)
        sessions.sendResult = { throw AppException.Unreachable() }
        model.onDraftChange("Run the tests"); model.send(); settle()
        val failed = model.uiState.value.pending.single()
        assertEquals(PendingMessage.State.FAILED, failed.state)
        assertEquals("", model.uiState.value.draft)
        sessions.sendResult = { InputReceipt("in-1", "queued", woke = false, reason = null) }
        model.retry(failed.clientMessageId); settle()
        assertEquals(PendingMessage.State.SENT, model.uiState.value.pending.single().state)
        assertEquals(2, sessions.calls.count { it == "send s1 Run the tests ${failed.clientMessageId}" })
        live.send(LiveUpdate.Item(TranscriptEvent.UserMessage(5, 5, "Run the tests", "in-1", "m1"))); settle()
        assertTrue(model.uiState.value.pending.isEmpty())
        // A message the server would refuse is said, not sent.
        model.onDraftChange("bell\u0007"); model.send(); settle()
        assertIs<SessionNotice.Text>(model.uiState.value.notice)
        assertEquals(2, sessions.calls.count { it.startsWith("send") })
        model.hidden()
    }

    @Test
    fun aMessageThatWokeAPausedSessionReadsTheHeaderAgain() = runTest(dispatcher) {
        sessions.session = talk.copy(status = CloudSessionStatus.PAUSED)
        val model = viewModel()
        model.visible(); settle()
        sessions.session = talk.copy(status = CloudSessionStatus.RESUMING)
        sessions.sendResult = { InputReceipt("in-1", "queued", woke = true, reason = null) }
        model.onDraftChange("Wake up"); model.send(); settle()
        assertEquals(CloudSessionStatus.RESUMING, model.uiState.value.session?.status)
        assertTrue(model.uiState.value.pending.single().woke)
        model.hidden()
    }

    @Test
    fun aSignedOutHeaderRefreshKeepsTheScreenAndTheEndedStateStays() = runTest(dispatcher) {
        val model = viewModel()
        model.visible(); settle()
        // The header read every 10 seconds meets an expired login: nothing escapes, the screen stays as it was.
        sessions.sessionFailure = AppException.Unauthorized()
        advanceTimeBy(11_000); runCurrent()
        assertEquals("s1", model.uiState.value.session?.id)
        assertTrue(sessions.calls.count { it == "get s1" } >= 2)
        // The stream ends while the header cannot be read: the final state stays.
        sessions.sessionFailure = AppException.Unreachable()
        live.send(LiveUpdate.Ended); settle()
        assertEquals(LiveState.ENDED, model.uiState.value.live)
        assertNull(model.uiState.value.error)
        model.hidden()
        assertEquals(LiveState.ENDED, model.uiState.value.live, "an ended stream stays ended")
    }

    @Test
    fun nothingToInterruptIsANoticeAndOneInterruptIsInFlightAtATime() = runTest(dispatcher) {
        val model = viewModel()
        model.visible(); settle()
        sessions.interruptResult = { throw AppException.Server(409, "nothing_to_interrupt") }
        model.interrupt(); settle()
        assertEquals(SessionNotice.NothingRunning, model.uiState.value.notice)
        sessions.interruptResult = { InputReceipt("int", "queued", woke = false, reason = null) }
        model.interrupt(); model.interrupt(); settle()
        assertEquals(2, sessions.calls.count { it == "interrupt s1" }, "a second tap while the first is on its way sends nothing")
        assertNull(model.uiState.value.notice)
        assertFalse(model.uiState.value.busy)
        sessions.interruptResult = { throw AppException.Server(409, "session_not_running") }
        model.interrupt(); settle()
        assertIs<SessionNotice.Text>(model.uiState.value.notice)
        model.hidden()
    }

    @Test
    fun theModelAndTheEffortChangeAndTheSessionShowsThem() = runTest(dispatcher) {
        val model = viewModel()
        model.visible(); settle()
        model.setModel("claude-sonnet-5-5"); settle()
        assertEquals("claude-sonnet-5-5", model.uiState.value.session?.model)
        model.setEffort("max"); settle()
        assertEquals("max", model.uiState.value.session?.effort)
        model.setEffort("two words"); settle()
        assertIs<SessionNotice.Text>(model.uiState.value.notice)
        assertEquals(1, sessions.calls.count { it.startsWith("setEffort") })
        model.hidden()
    }

    @Test
    fun onlyTheOwnerControlsTheSession() = runTest(dispatcher) {
        val model = viewModel(memberId = "m2")
        model.visible(); settle()
        assertFalse(model.uiState.value.canControl)
        assertEquals("m2", model.uiState.value.memberId)
        live.send(LiveUpdate.Item(toolEvent(5, "t1"))); settle()
        assertFalse(model.uiState.value.bypassOffered, "Bypass is the owner's")
        model.hidden()
    }

    // ---- Permission requests -------------------------------------------------------------------------------------

    private fun options(vararg labels: String) = labels.map { AgentQuestionOption(it, "About $it") }
    private val colour = AgentQuestion(0, "Red or blue?", "Colour", options("Red", "Blue"), multiSelect = false)
    private val sizes = AgentQuestion(1, "Which sizes?", null, options("S", "M", "L"), multiSelect = true)
    private val name = AgentQuestion(2, "Its name?", null, options("Ada"), multiSelect = false)
    private fun askEvent(seq: Long, id: String = "r1", questions: List<AgentQuestion> = listOf(colour, sizes, name)) =
        TranscriptEvent.PermissionRequest(seq, seq, id, "AskUserQuestion", true, questions, input = "{}")
    private fun toolEvent(seq: Long, id: String, leftOut: Boolean = false) = TranscriptEvent.PermissionRequest(
        seq, seq, id, "Bash", false, input = if (leftOut) null else "{\"command\": \"rm -rf build\"}", inputLeftOut = leftOut,
        inputPreview = if (leftOut) "{\"command\":" else null,
    )

    @Test
    fun aQuestionEventInTheTranscriptYieldsAnAnswerableCard() = runTest(dispatcher) {
        firstPage = TranscriptPage(listOf(TranscriptEvent.Turn(3, 3, TurnPhase.STARTED, null, null), askEvent(4)), nextBefore = null, lastSeq = 4)
        val model = viewModel()
        model.visible(); settle()
        val card = assertNotNull(model.uiState.value.permission)
        assertEquals("r1", card.requestId)
        assertTrue(card.answerable)
        assertEquals(colour, card.current)
        assertEquals(0, card.waiting)
        model.hidden()
    }

    @Test
    fun withoutThePermissionsKeyNoCardShows() = runTest(dispatcher) {
        features.features -= Features.RUNNERS_CLOUD_PERMISSIONS
        firstPage = TranscriptPage(listOf(askEvent(4)), nextBefore = null, lastSeq = 4)
        val model = viewModel()
        model.visible(); settle()
        assertNull(model.uiState.value.permission)
        model.hidden()
    }

    @Test
    fun questionsShowOneAtATimeBackKeepsTheDraftAndSendPostsEveryAnswerOnce() = runTest(dispatcher) {
        val model = viewModel()
        model.visible(); settle()
        live.send(LiveUpdate.Item(askEvent(5))); settle()
        // Nothing chosen: no next question.
        model.nextQuestion("r1")
        assertEquals(colour, model.uiState.value.permission?.current)
        model.selectOption("r1", 0, 1); model.nextQuestion("r1")
        assertEquals(sizes, model.uiState.value.permission?.current)
        model.selectOption("r1", 1, 2); model.selectOption("r1", 1, 0)
        // Back keeps both drafts.
        model.previousQuestion("r1")
        val back = assertNotNull(model.uiState.value.permission)
        assertEquals(colour, back.current)
        assertEquals(setOf(1), back.draft.choices[0]?.options)
        model.nextQuestion("r1")
        assertEquals(setOf(0, 2), model.uiState.value.permission?.draft?.choices?.get(1)?.options)
        model.nextQuestion("r1")
        val last = assertNotNull(model.uiState.value.permission)
        assertTrue(last.last)
        assertEquals(name, last.current)
        // "Other" is exclusive in a single choice: typing picks it over the option.
        model.selectOption("r1", 2, 0); model.onOtherText("r1", 2, "  Grace  ")
        assertEquals(QuestionChoice(emptySet(), other = true, otherText = "  Grace  "), model.uiState.value.permission?.draft?.choices?.get(2))
        model.sendAnswers("r1"); settle()
        val (answer, _) = sessions.answers.single()
        assertEquals(PermissionAnswer("r1", allow = true, answers = listOf(QuestionAnswer(0, listOf(1)), QuestionAnswer(1, listOf(0, 2)), QuestionAnswer(2, text = "Grace"))), answer)
        assertEquals(AnswerState.SENT, model.uiState.value.permission?.draft?.state)
        // Once sent, nothing changes it.
        model.selectOption("r1", 2, 0)
        assertEquals(AnswerState.SENT, model.uiState.value.permission?.draft?.state)
        // The agent took it: the card goes.
        live.send(LiveUpdate.Item(TranscriptEvent.InputChanged(6, 6, "ans-r1", "consumed", null, requestId = "r1"))); settle()
        assertNull(model.uiState.value.permission)
        assertTrue(model.uiState.value.drafts.isEmpty())
        model.hidden()
    }

    @Test
    fun aFailedAnswerIsRetriedWithTheSameKeyAndAnEditedOneGetsANewKey() = runTest(dispatcher) {
        val model = viewModel()
        model.visible(); settle()
        live.send(LiveUpdate.Item(askEvent(5, questions = listOf(colour)))); settle()
        model.selectOption("r1", 0, 0)
        sessions.answerResult = { throw AppException.Unreachable() }
        model.sendAnswers("r1"); settle()
        assertEquals(AnswerState.FAILED, model.uiState.value.permission?.draft?.state)
        sessions.answerResult = { throw AppException.Server(503, null) }
        model.retryAnswer("r1"); settle()
        sessions.answerResult = { throw AppException.RateLimited(5) }
        model.retryAnswer("r1"); settle()
        assertEquals(AnswerState.FAILED, model.uiState.value.permission?.draft?.state)
        // An edit is another answer: it goes under a new key.
        model.selectOption("r1", 0, 1)
        assertEquals(AnswerState.EDITING, model.uiState.value.permission?.draft?.state)
        sessions.answerResult = { InputReceipt("ans", "queued", woke = false, reason = null) }
        model.sendAnswers("r1"); settle()
        val keys = sessions.answers.map { it.second }
        assertEquals(4, keys.size)
        assertEquals(1, keys.take(3).toSet().size)
        assertNotEquals(keys[0], keys[3])
        assertEquals(listOf(0, 0, 0, 1), sessions.answers.map { it.first.answers.single().options.single() })
        assertEquals(AnswerState.SENT, model.uiState.value.permission?.draft?.state)
        model.hidden()
    }

    @Test
    fun aConflictClosesTheCardWithANoticeAndAnInvalidAnswerKeepsTheDraft() = runTest(dispatcher) {
        val model = viewModel()
        model.visible(); settle()
        live.send(LiveUpdate.Item(askEvent(5, questions = listOf(colour)))); settle()
        live.send(LiveUpdate.Item(askEvent(6, id = "r2", questions = listOf(sizes)))); settle()
        assertEquals(1, model.uiState.value.permission?.waiting)
        model.selectOption("r1", 0, 0)
        sessions.answerResult = { throw AppException.Server(400, "permission_input_unseen") }
        model.sendAnswers("r1"); settle()
        val kept = assertNotNull(model.uiState.value.permission)
        assertEquals("r1", kept.requestId)
        assertEquals(AnswerState.EDITING, kept.draft.state)
        assertEquals(setOf(0), kept.draft.choices[0]?.options)
        assertEquals(SessionNotice.PermissionInvalid("permission_input_unseen"), model.uiState.value.notice)
        sessions.answerResult = { throw AppException.Server(409, "permission_request_answered") }
        model.sendAnswers("r1"); settle()
        assertEquals(SessionNotice.PermissionClosed("permission_request_answered"), model.uiState.value.notice)
        // The next open request is shown.
        assertEquals("r2", model.uiState.value.permission?.requestId)
        assertNotEquals(sessions.answers[0].second, sessions.answers[1].second)
        model.hidden()
    }

    @Test
    fun declineDeniesAQuestionAndAToolUseIsAllowedOrDeniedOnlyWhenItsInputWasShown() = runTest(dispatcher) {
        val model = viewModel()
        model.visible(); settle()
        live.send(LiveUpdate.Item(askEvent(5))); settle()
        model.selectOption("r1", 0, 0); model.nextQuestion("r1")
        model.deny("r1"); settle()
        assertEquals(PermissionAnswer("r1", allow = false), sessions.answers.last().first)
        live.send(LiveUpdate.Item(TranscriptEvent.InputChanged(6, 6, "ans-r1", "consumed", null, requestId = "r1")))
        live.send(LiveUpdate.Item(toolEvent(7, "t1"))); settle()
        val tool = assertNotNull(model.uiState.value.permission)
        assertTrue(tool.answerable)
        // Only the owner instructs an agent that uses their GitHub access: anyone else is told so and may still deny.
        sessions.answerResult = { throw AppException.Server(403, "github_owner_only") }
        model.allowTool("t1"); settle()
        assertEquals(SessionNotice.PermissionDenied, model.uiState.value.notice)
        assertEquals(PermissionAnswer("t1", allow = true), sessions.answers.last().first)
        sessions.answerResult = { InputReceipt("ans", "queued", woke = false, reason = null) }
        live.send(LiveUpdate.Item(TranscriptEvent.Turn(8, 8, TurnPhase.COMPLETED, null, null)))
        live.send(LiveUpdate.Item(toolEvent(9, "t2", leftOut = true))); settle()
        val leftOut = assertNotNull(model.uiState.value.permission)
        assertEquals("t2", leftOut.requestId)
        assertFalse(leftOut.answerable)
        val before = sessions.answers.size
        model.allowTool("t2"); settle()
        assertEquals(before, sessions.answers.size)
        model.deny("t2"); settle()
        assertEquals(PermissionAnswer("t2", allow = false), sessions.answers.last().first)
        // Another refusal is worded by its key.
        sessions.answerResult = { throw AppException.Server(403, "forbidden") }
        live.send(LiveUpdate.Item(TranscriptEvent.InputChanged(10, 10, "ans", "consumed", null, requestId = "t2")))
        live.send(LiveUpdate.Item(toolEvent(11, "t3"))); settle()
        model.deny("t3"); settle()
        assertIs<SessionNotice.Text>(model.uiState.value.notice)
        model.hidden()
    }

    @Test
    fun bypassRecordsTheApprovalThenAllowsTheOpenRequestAndCanBeTakenBack() = runTest(dispatcher) {
        val model = viewModel()
        model.visible(); settle()
        live.send(LiveUpdate.Item(toolEvent(5, "t1"))); settle()
        assertTrue(model.uiState.value.bypassOffered, "The owner of a session on a server that supports it is offered Bypass")
        model.bypass("t1"); settle()
        assertEquals("bypass s1 true", sessions.calls.last { it.startsWith("bypass") })
        assertEquals(PermissionAnswer("t1", allow = true), sessions.answers.last().first, "The request open now is allowed too")
        assertTrue(model.uiState.value.session!!.permissionBypass)
        assertFalse(model.uiState.value.bypassOffered, "Once it is on, the card no longer offers it")
        model.revokeBypass(); settle()
        assertFalse(model.uiState.value.session!!.permissionBypass)
        assertTrue(model.uiState.value.bypassOffered)
        model.hidden()
    }

    @Test
    fun aRefusedBypassAllowsNothing() = runTest(dispatcher) {
        val model = viewModel()
        model.visible(); settle()
        live.send(LiveUpdate.Item(toolEvent(5, "t1"))); settle()
        sessions.changeResult = { throw AppException.Server(403, "bypass_owner_only") }
        val before = sessions.answers.size
        model.bypass("t1"); settle()
        assertEquals(before, sessions.answers.size, "The request stays open for the member")
        assertNotNull(model.uiState.value.notice)
        model.hidden()
    }

    @Test
    fun anAcceptedAnswerTheAgentRejectedCanBeSentAgain() = runTest(dispatcher) {
        val model = viewModel()
        model.visible(); settle()
        live.send(LiveUpdate.Item(askEvent(5, questions = listOf(colour)))); settle()
        model.selectOption("r1", 0, 0); model.sendAnswers("r1"); settle()
        live.send(LiveUpdate.Item(TranscriptEvent.InputChanged(6, 6, "ans-r1", "rejected", "agent_unavailable", requestId = "r1"))); settle()
        val card = assertNotNull(model.uiState.value.permission)
        assertEquals(AnswerState.EDITING, card.draft.state)
        assertEquals(SessionNotice.PermissionRejected("agent_unavailable"), model.uiState.value.notice)
        model.sendAnswers("r1"); settle()
        assertEquals(2, sessions.answers.size)
        assertNotEquals(sessions.answers[0].second, sessions.answers[1].second)
        model.hidden()
    }

    @Test
    fun aPausingSessionShowsNoRequest() = runTest(dispatcher) {
        firstPage = TranscriptPage(listOf(askEvent(4)), nextBefore = null, lastSeq = 4)
        sessions.session = talk.copy(status = CloudSessionStatus.PAUSING)
        val model = viewModel()
        model.visible(); settle()
        assertNull(model.uiState.value.permission)
        model.hidden()
    }

    @Test
    fun aSubagentOpensInPlaceOfTheMainThreadAndThePanelOpensAndCloses() = runTest(dispatcher) {
        val model = viewModel()
        model.visible(); settle()
        assertNull(model.uiState.value.thread)
        model.openThread("a1")
        assertEquals("a1", model.uiState.value.thread)
        model.openThread("a2")
        assertEquals("a2", model.uiState.value.thread, "Switching goes straight from one subagent to another")
        model.backToMain()
        assertNull(model.uiState.value.thread)
        assertFalse(model.uiState.value.panelOpen)
        model.togglePanel(); assertTrue(model.uiState.value.panelOpen)
        model.closePanel(); assertFalse(model.uiState.value.panelOpen)
        model.togglePanelHidden(); assertTrue(model.uiState.value.panelHidden)
        model.hidden()
    }

    @Test
    fun stoppingABackgroundTaskAsksFirstThenSendsOneStop() = runTest(dispatcher) {
        val model = viewModel()
        model.visible(); settle()
        model.askStop("b1")
        assertEquals("b1", model.uiState.value.confirmStop)
        assertTrue(sessions.calls.none { it.startsWith("stopTask") }, "Nothing is stopped before it is confirmed")
        model.cancelStop()
        assertNull(model.uiState.value.confirmStop)
        assertTrue(sessions.calls.none { it.startsWith("stopTask") })
        model.askStop("b1"); model.confirmStop(); settle()
        assertNull(model.uiState.value.confirmStop)
        assertEquals(1, sessions.calls.count { it == "stopTask s1 b1" })
        assertEquals(setOf("b1"), model.uiState.value.stopping, "Shown as stopping until the runner's next report drops it")
        live.send(LiveUpdate.Item(TranscriptEvent.Tasks(60, 60, emptyList(), stoppable = true))); settle()
        assertTrue(model.uiState.value.stopping.isEmpty(), "The report no longer lists it")
        // The session stopped meanwhile, or the server refused: said, and nothing stays "stopping".
        sessions.stopTaskResult = { throw AppException.Server(409, "nothing_to_interrupt") }
        model.askStop("b2"); model.confirmStop(); settle()
        assertEquals(SessionNotice.NothingRunning, model.uiState.value.notice)
        assertTrue(model.uiState.value.stopping.isEmpty())
        sessions.stopTaskResult = { throw AppException.NotFound() }
        model.askStop("b3"); model.confirmStop(); settle()
        assertIs<SessionNotice.Text>(model.uiState.value.notice)
        model.hidden()
    }
}
