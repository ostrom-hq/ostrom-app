// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../presentation/TranscriptFeedsTest.kt; see NOTICE.
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
import run.ostrom.app.domain.models.BackgroundTask
import run.ostrom.app.domain.models.CloudSessionMode
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.KnownAgent
import run.ostrom.app.domain.models.KnownAgentState
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.TranscriptEvent
import run.ostrom.app.domain.models.TranscriptPage
import run.ostrom.app.domain.repositories.TranscriptRepository
import run.ostrom.app.domain.usecases.transcript.Transcript
import run.ostrom.app.presentation.transcript.SubagentState
import run.ostrom.app.presentation.transcript.subagentsOf
import run.ostrom.app.presentation.viewmodels.CloudSessionViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A feed per agent (`runners.cloud.transcript.feeds.v1`): the conversation opens on the main agent's own events, each subagent
 * pages its own events with its own cursor, the server's list of subagents completes what the loaded events show, and a
 * server without the capability keeps the one feed it always had.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptFeedsTest {
    private val dispatcher = StandardTestDispatcher()
    private val live = Channel<LiveUpdate<TranscriptEvent>>(Channel.UNLIMITED)

    /** One request: the cursor and the feed it carried (null: every event). */
    private data class Request(val before: Long?, val feed: String?)

    private val requests = mutableListOf<Request>()
    private var agentReads = 0
    private var agents = listOf<KnownAgent>()
    private val mainPage = TranscriptPage(listOf(TranscriptEvent.AgentMessage(40, 40, "Main", null)), nextBefore = 40, lastSeq = 90)
    private val olderMain = TranscriptPage(listOf(TranscriptEvent.AgentMessage(20, 20, "Older main", null)), nextBefore = null, lastSeq = 90)
    private val workerPage = TranscriptPage(listOf(TranscriptEvent.AgentMessage(70, 70, "Worker", "w1")), nextBefore = 70, lastSeq = 90)
    private val olderWorker = TranscriptPage(listOf(TranscriptEvent.AgentMessage(50, 50, "Older worker", "w1")), nextBefore = null, lastSeq = 90)

    private val transcripts = object : TranscriptRepository {
        override suspend fun page(accountId: String, sessionId: String, before: Long?): TranscriptPage = feedPage(accountId, sessionId, before, null)
        override suspend fun feedPage(accountId: String, sessionId: String, before: Long?, feed: String?): TranscriptPage {
            requests += Request(before, feed)
            return when {
                feed == "w1" && before == null -> workerPage
                feed == "w1" -> olderWorker
                before == null -> mainPage
                else -> olderMain
            }
        }
        override suspend fun agents(accountId: String, sessionId: String): List<KnownAgent> { agentReads++; return agents }
        override fun follow(accountId: String, sessionId: String, after: Long): Flow<LiveUpdate<TranscriptEvent>> = live.receiveAsFlow()
    }
    private val sessions = FakeCloudSessionRepository(cloudSession("s1").copy(mode = CloudSessionMode.CONVERSATION))

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(feeds: Boolean = true): CloudSessionViewModel {
        val features = FakeFeatures(setOf(Features.RUNNERS_CLOUD, Features.RUNNERS_CLOUD_CONVERSATION))
        if (feeds) features.features += Features.RUNNERS_CLOUD_TRANSCRIPT_FEEDS
        return conversationViewModel(sessions, transcripts, features)
    }

    private fun TestScope.settle() { advanceTimeBy(1_500); runCurrent() }

    private fun known(id: String, state: KnownAgentState = KnownAgentState.RUNNING, steps: Int = 3, startedAt: Long = 1_000, endedAt: Long? = null, parentId: String? = null, taskId: String? = null) =
        KnownAgent(id, "reviewer", "Check the tests", state, startedAt, endedAt, steps, parentId, taskId)

    @Test
    fun theConversationOpensOnTheMainFeedAndGoesUpOnIt() = runTest(dispatcher) {
        val model = viewModel()
        model.visible(); settle()
        assertEquals(listOf(Request(null, "main")), requests, "the first page is the main agent's own")
        assertTrue(model.uiState.value.feeds)

        model.loadOlder(); settle()
        assertEquals(Request(40, "main"), requests.last(), "going up asks the same feed, from its own cursor")
        assertNull(model.uiState.value.olderOf(null), "everything older was read")
        assertEquals(listOf(20L, 40L), model.uiState.value.transcript.events.map { it.seq })
        model.hidden()
    }

    @Test
    fun aSubagentThreadHasItsOwnFeedAndItsOwnCursor() = runTest(dispatcher) {
        val model = viewModel()
        model.visible(); settle()
        assertTrue(!model.uiState.value.threadLoaded("w1"))

        model.openThread("w1"); settle()
        assertEquals(Request(null, "w1"), requests.last(), "opening a thread reads the newest page of its feed")
        assertTrue(model.uiState.value.threadLoaded("w1"))
        assertEquals(70L, model.uiState.value.olderOf("w1"))
        assertEquals(40L, model.uiState.value.olderOf(null), "the main agent's cursor did not move")

        model.loadOlder(); settle()
        assertEquals(Request(70, "w1"), requests.last(), "going up in a thread loads that thread")
        assertNull(model.uiState.value.olderOf("w1"))
        assertEquals(40L, model.uiState.value.olderOf(null))

        model.backToMain(); model.loadOlder(); settle()
        assertEquals(Request(40, "main"), requests.last(), "back on the main thread, going up loads the main feed")
        assertEquals(listOf(20L, 40L, 50L, 70L), model.uiState.value.transcript.events.map { it.seq })
        model.hidden()
    }

    @Test
    fun anOpenedThreadIsReadOnce() = runTest(dispatcher) {
        val model = viewModel()
        model.visible(); settle()
        model.openThread("w1"); model.openThread("w1"); settle()
        assertEquals(1, requests.count { it.feed == "w1" }, "two taps read it once")
        model.backToMain(); model.openThread("w1"); settle()
        assertEquals(1, requests.count { it.feed == "w1" }, "an opened feed is kept")
        model.hidden()
    }

    @Test
    fun theServersListIsReadAtOpeningAndAgainWhenASubagentStartsOrTasksMove() = runTest(dispatcher) {
        agents = listOf(known("w1"))
        val model = viewModel()
        model.visible(); settle()
        assertEquals(1, agentReads)
        assertEquals(listOf("w1"), model.uiState.value.agents.map { it.id })

        agents = listOf(known("w1"), known("w2", startedAt = 5_000))
        live.send(LiveUpdate.Item(TranscriptEvent.ToolCall(91, 91, "w2", "Agent", "{}", null, null))); settle()
        assertEquals(2, agentReads, "a subagent started")
        assertEquals(2, model.uiState.value.agents.size)

        live.send(LiveUpdate.Item(TranscriptEvent.AgentMessage(92, 92, "just text", null))); settle()
        assertEquals(2, agentReads, "a message changes nothing about the agents")

        live.send(LiveUpdate.Item(TranscriptEvent.Tasks(93, 93, emptyList(), true))); settle()
        assertEquals(3, agentReads, "a task report")
        model.hidden()
    }

    @Test
    fun aBurstOfEventsCostsTwoReadsAtMost() = runTest(dispatcher) {
        val model = viewModel()
        model.visible(); settle()
        val before = agentReads
        repeat(10) { live.send(LiveUpdate.Item(TranscriptEvent.Tasks(100L + it, 1, emptyList(), true))) }
        settle()
        assertTrue(agentReads - before <= 2, "reads: ${agentReads - before}")
        model.hidden()
    }

    @Test
    fun withoutTheCapabilityTheFeedIsNeverAskedForAndNothingElseChanges() = runTest(dispatcher) {
        val model = viewModel(feeds = false)
        model.visible(); settle()
        assertEquals(listOf(Request(null, null)), requests, "one feed, as before")
        assertEquals(0, agentReads, "no list of subagents")
        assertTrue(!model.uiState.value.feeds)

        model.openThread("w1"); settle()
        assertEquals(1, requests.size, "a thread is read from the loaded events")
        assertEquals(40L, model.uiState.value.olderOf("w1"), "one cursor serves every thread")
        model.loadOlder(); settle()
        assertEquals(Request(40, null), requests.last())
        model.hidden()
    }

    // ---- The server's list completes the loaded events ---------------------------------------------------------------

    private fun call(seq: Long, id: String, parent: String? = null) =
        TranscriptEvent.ToolCall(seq, seq * 1_000, id, "Agent", """{"description":"Check the tests","subagent_type":"reviewer"}""", "Check the tests", parent)

    @Test
    fun aSubagentWhoseCallWasNotLoadedIsListedWithItsRealStartAndState() {
        val events = listOf(TranscriptEvent.AgentMessage(90, 90_000, "Recent", null))
        val shown = subagentsOf(events, running = true, tasks = null, now = 100_000, known = listOf(
            known("old", KnownAgentState.COMPLETED, steps = 12, startedAt = 5_000, endedAt = 65_000),
            known("busy", KnownAgentState.RUNNING, steps = 4, startedAt = 50_000),
            known("lost", KnownAgentState.FAILED, startedAt = 20_000, endedAt = 21_000),
        ))
        assertEquals(listOf("old", "lost", "busy"), shown.map { it.id }, "in the order they started")
        val old = shown.first { it.id == "old" }
        assertEquals(SubagentState.DONE, old.state)
        assertEquals(12, old.steps)
        assertEquals(5_000, old.startedAt)
        assertEquals(60_000, old.durationMs)
        assertEquals(SubagentState.FAILED, shown.first { it.id == "lost" }.state)
        val busy = shown.first { it.id == "busy" }
        assertEquals(SubagentState.RUNNING, busy.state)
        assertEquals(50_000, busy.durationMs, "a running one lasts until now")
    }

    @Test
    fun aRunningSubagentOfASessionThatIsNotRunningIsStopped() {
        val shown = subagentsOf(emptyList(), running = false, tasks = null, now = 100_000, known = listOf(known("busy")))
        assertEquals(SubagentState.STOPPED, shown.single().state)
    }

    @Test
    fun theLiveTaskReportKeepsASubagentRunningAndGivesItsStop() {
        val task = BackgroundTask("t1", "local_agent", "Check the tests", "reviewer", "busy", 1_000)
        val shown = subagentsOf(emptyList(), running = true, tasks = listOf(task), now = 9_000, known = listOf(known("busy", KnownAgentState.COMPLETED, taskId = "t1")))
        assertEquals(SubagentState.RUNNING, shown.single().state, "the runner's report is newer than the list")
        assertEquals("t1", shown.single().taskId)
    }

    @Test
    fun aLoadedCallGetsTheServersStartStepsAndEnd() {
        val events = listOf(call(80, "w1"))
        val shown = subagentsOf(events, running = true, tasks = null, now = 200_000, known = listOf(known("w1", KnownAgentState.COMPLETED, steps = 9, startedAt = 70_000, endedAt = 150_000))).single()
        assertEquals(SubagentState.DONE, shown.state, "the server says it ended before its result was loaded")
        assertEquals(9, shown.steps, "its own steps were never loaded")
        assertEquals(70_000, shown.startedAt)
        assertEquals(80_000, shown.durationMs)
    }

    @Test
    fun theDepthFollowsTheParentsOfTheServersList() {
        val shown = subagentsOf(emptyList(), running = true, tasks = null, now = 9_000, known = listOf(
            known("a", startedAt = 1_000), known("b", startedAt = 2_000, parentId = "a"), known("c", startedAt = 3_000, parentId = "b"),
        ))
        assertEquals(listOf(0, 1, 2), shown.map { it.depth })
    }

    @Test
    fun withoutAServerListNothingChanges() {
        val events = listOf(call(80, "w1"))
        assertEquals(subagentsOf(events, running = true, tasks = null, now = 200_000), subagentsOf(events, running = true, tasks = null, now = 200_000, known = emptyList()))
    }

    @Test
    fun aFeedPageMovesOnlyItsOwnCursor() {
        val main = Transcript().withLatest(mainPage)
        val withWorker = main.withFeed("w1", null, workerPage)
        assertEquals(40L, withWorker.nextBefore)
        assertEquals(mapOf<String, Long?>("w1" to 70L), withWorker.feeds)
        assertEquals(main.newestSeq, 90L)
        assertEquals(90L, withWorker.newestSeq, "a feed page never raises the newest sequence")
        assertEquals(mapOf<String, Long?>("w1" to null), withWorker.withFeed("w1", 70L, olderWorker).feeds)
        assertEquals(40L, withWorker.withFeed("w1", 70L, olderWorker).nextBefore)
    }

    @Test
    fun aPageAskedFromACursorNoLongerCurrentAddsItsEventsButNeverMovesTheCursorBack() {
        val main = Transcript().withLatest(mainPage)
        // The main thread: asked before 40, then again before 40 once the first answer moved the cursor to the end.
        val older = main.withOlder(40L, olderMain)
        assertNull(older.nextBefore)
        val late = older.withOlder(40L, TranscriptPage(listOf(TranscriptEvent.AgentMessage(30, 30, "Late", null)), nextBefore = 30, lastSeq = 90))
        assertNull(late.nextBefore, "the cursor stays where the newer answer put it")
        assertEquals(listOf(20L, 30L, 40L), late.events.map { it.seq })
        assertEquals(40L, main.withOlder(30L, olderMain).nextBefore, "asked from a cursor the thread no longer has")
        // A feed: its newest page arriving after it was read, and an older page asked from a cursor it no longer has.
        val read = main.withFeed("w1", null, workerPage).withFeed("w1", 70L, olderWorker)
        assertEquals(mapOf<String, Long?>("w1" to null), read.withFeed("w1", null, workerPage).feeds)
        assertEquals(mapOf<String, Long?>("w1" to null), read.withFeed("w1", 70L, workerPage).feeds)
    }
}
