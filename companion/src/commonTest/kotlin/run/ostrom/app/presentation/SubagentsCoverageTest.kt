// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import run.ostrom.app.domain.models.BackgroundTask
import run.ostrom.app.domain.models.KnownAgent
import run.ostrom.app.domain.models.KnownAgentState
import run.ostrom.app.domain.models.TranscriptEvent
import run.ostrom.app.domain.models.TurnPhase
import run.ostrom.app.presentation.transcript.BackgroundCommand
import run.ostrom.app.presentation.transcript.Subagent
import run.ostrom.app.presentation.transcript.SubagentState
import run.ostrom.app.presentation.transcript.backgroundCommands
import run.ostrom.app.presentation.transcript.subagentsOf
import run.ostrom.app.presentation.transcript.threadEvents
import run.ostrom.app.presentation.transcript.watches
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The edges of the subagents read from the transcript: unreadable inputs, every state, the server's list and the thread of a subagent. */
class SubagentsCoverageTest {
    private fun call(seq: Long, id: String, name: String = "Agent", input: String = "{}", summary: String? = "sum", parent: String? = null) =
        TranscriptEvent.ToolCall(seq, seq * 1_000, id, name, input, summary, parent)
    private fun result(seq: Long, id: String, parent: String? = null, error: Boolean = false) = TranscriptEvent.ToolResult(seq, seq * 1_000, id, "ok", error, null, parent)
    private fun text(seq: Long, text: String, parent: String?) = TranscriptEvent.AgentMessage(seq, seq * 1_000, text, parent)
    private fun turn(seq: Long, phase: TurnPhase) = TranscriptEvent.Turn(seq, seq * 1_000, phase, null, null)
    private fun task(id: String, toolUseId: String?, type: String = "local_agent", startedAt: Long? = null) = BackgroundTask(id, type, "d", null, toolUseId, startedAt)
    private fun known(
        id: String, state: KnownAgentState, startedAt: Long = 1_000, endedAt: Long? = null, steps: Int = 0, parent: String? = null, taskId: String? = null, type: String? = "Explore",
    ) = KnownAgent(id, type, "Known", state, startedAt, endedAt, steps, parent, taskId)
    private fun background(seq: Long, id: String) = call(seq, id, input = """{"run_in_background":true}""")

    private fun List<Subagent>.of(id: String) = single { it.id == id }

    @Test fun onlyRunningAndBackgroundSubagentsAreActive() {
        fun subagent(state: SubagentState) = Subagent("a", "t", "d", state, 0, 0, 0, null, 0, null)
        assertTrue(subagent(SubagentState.RUNNING).active)
        assertTrue(subagent(SubagentState.BACKGROUND).active)
        assertFalse(subagent(SubagentState.DONE).active)
        assertFalse(subagent(SubagentState.FAILED).active)
        assertFalse(subagent(SubagentState.STOPPED).active)
    }

    @Test fun aToolUseWaitsOnlyWhenItIsTheMonitorOrABashCommandThatWaits() {
        fun bash(input: String, name: String = "Bash") = call(1, "c", name = name, input = input)
        assertTrue(watches(bash("{}", name = "Monitor")))
        assertTrue(watches(bash("""{"command":"sleep 30"}""")))
        assertFalse(watches(bash("""{"command":"./gradlew build"}""")))
        assertFalse(watches(bash("{}")), "No command")
        assertFalse(watches(bash("""{"command":5}""")), "A command that is not text")
        assertFalse(watches(bash("""{"command":null}""")))
        assertFalse(watches(bash("""{"command":{"a":1}}""")))
        assertFalse(watches(bash("not json")), "An input that cannot be read")
        assertFalse(watches(bash("""["sleep 30"]""")), "An input that is not an object")
        assertFalse(watches(bash("""{"command":"sleep 30"}""", name = "Read")), "Only Bash and Monitor")
    }

    @Test fun anAgentCallNeedsAnIdAndTheNameOfAnAgentTool() {
        assertEquals(emptyList(), subagentsOf(listOf(call(1, ""), call(2, "x", name = "Read")), running = true, tasks = null, now = 5_000))
    }

    @Test fun anInputThatCannotBeReadGivesTheDefaultTypeAndTheCallsSummary() {
        val events = listOf(
            call(1, "garbled", input = "not json", summary = null),
            call(2, "blank", input = """{"subagent_type":"  ","description":"  "}""", summary = "Fallback"),
            call(3, "array", input = """["x"]"""),
            call(4, "odd", input = """{"subagent_type":5,"description":{"a":1}}""", summary = "Odd"),
        )
        val agents = subagentsOf(events, running = true, tasks = null, now = 10_000)
        assertEquals(listOf("general-purpose"), agents.map { it.type }.distinct())
        assertEquals(listOf("", "Fallback", "sum", "Odd"), agents.map { it.description })
        assertEquals(listOf(SubagentState.RUNNING), agents.map { it.state }.distinct())
        assertEquals(9_000L, agents.of("garbled").durationMs)
    }

    @Test fun onlyTrueRunInBackgroundMakesABackgroundSubagent() {
        val events = listOf(
            call(1, "no", input = """{"run_in_background":false}"""), result(2, "no"),
            call(3, "text", input = """{"run_in_background":"yes"}"""), result(4, "text"),
            call(5, "object", input = """{"run_in_background":{}}"""), result(6, "object"),
            background(7, "yes"), result(8, "yes"),
        )
        val agents = subagentsOf(events, running = true, tasks = null, now = 20_000)
        assertEquals(listOf(SubagentState.DONE, SubagentState.DONE, SubagentState.DONE), listOf("no", "text", "object").map { agents.of(it).state })
        assertEquals(SubagentState.BACKGROUND, agents.of("yes").state, "Only its launch is known while the session runs and no task is reported")
        assertEquals(13_000L, agents.of("yes").durationMs)
        // A session that is not running runs nothing: the launched one is over, at its last event.
        val over = subagentsOf(events, running = false, tasks = null, now = 20_000)
        assertEquals(SubagentState.DONE, over.of("yes").state)
        assertEquals(1_000L, over.of("yes").durationMs, "Its result only said it was launched, and nothing of its own came after")
    }

    @Test fun aTaskIsMatchedBySubagentTypeAndToolUseAndBoundsTheStart() {
        val events = listOf(call(1, "s1"), call(2, "s2"), call(3, "s3"), call(4, "s4"))
        val tasks = listOf(
            task("t-bash", "s4", type = "local_bash"),
            task("t-elsewhere", "nobody"),
            task("t1", "s1", startedAt = null),
            task("t2", "s2", startedAt = 500),
            task("t3", "s3", startedAt = 99_000),
        )
        val agents = subagentsOf(events, running = true, tasks = tasks, now = 10_000)
        assertEquals(listOf("t1", "t2", "t3", null), agents.map { it.taskId })
        assertEquals(listOf(1_000L, 500L, 3_000L, 4_000L), agents.map { it.startedAt }, "The runner's clock never puts the start after the call")
        assertEquals(SubagentState.RUNNING, agents.of("s4").state, "A task that is not a subagent's does not match")
    }

    @Test fun aSubagentThatEndedWithoutResultEndsAtItsLastEventOrItsCall() {
        val events = listOf(
            call(1, "quiet"),
            call(2, "busy"), text(3, "Thinking.", parent = "busy"), text(4, "More.", parent = "busy"),
            background(5, "away"), text(8, "Later.", parent = "away"),
            background(9, "silent"),
            turn(10, TurnPhase.COMPLETED),
        )
        val agents = subagentsOf(events, running = true, tasks = null, now = 50_000)
        assertEquals(listOf(SubagentState.STOPPED), agents.map { it.state }.distinct())
        assertEquals(0L, agents.of("quiet").durationMs)
        assertEquals(2_000L, agents.of("busy").durationMs)
        assertEquals(3_000L, agents.of("away").durationMs, "A background one: the later of its call and its last event")
        assertEquals(0L, agents.of("silent").durationMs)
        // Not running and no turn ended: stopped too.
        assertEquals(SubagentState.STOPPED, subagentsOf(listOf(call(1, "x")), running = false, tasks = null, now = 5_000).single().state)
        assertEquals(SubagentState.RUNNING, subagentsOf(listOf(call(1, "x"), turn(0, TurnPhase.COMPLETED)), running = true, tasks = null, now = 5_000).single().state, "A turn that ended before it")
    }

    @Test fun aBackgroundSubagentEndsAtTheLaterOfItsResultAndItsLastEvent() {
        val launched = listOf(background(1, "b"), result(2, "b"))
        assertEquals(1_000L, subagentsOf(launched, running = true, tasks = emptyList(), now = 9_000).single().durationMs)
        val worked = launched + text(5, "Done.", parent = "b")
        assertEquals(4_000L, subagentsOf(worked, running = true, tasks = emptyList(), now = 9_000).single().durationMs)
    }

    @Test fun theDepthStopsAtAnUnknownParentAndAtALoop() {
        val orphan = subagentsOf(listOf(call(1, "o", parent = "ghost")), running = true, tasks = null, now = 5_000).single()
        assertEquals(0, orphan.depth)
        assertEquals("ghost", orphan.parentId)
        val loop = subagentsOf(listOf(call(1, "p", parent = "q"), call(2, "q", parent = "p")), running = true, tasks = null, now = 5_000)
        assertEquals(listOf(16, 16), loop.map { it.depth })
    }

    // The server's list.

    private fun merged(events: List<TranscriptEvent>, vararg list: KnownAgent, running: Boolean = true, tasks: List<BackgroundTask>? = null) =
        subagentsOf(events, running, tasks, now = 100_000, known = list.toList())

    @Test fun aKnownAgentCompletesWhatTheEventsSayOfARunningSubagent() {
        val events = listOf(call(1, "a"))
        val done = merged(events, known("a", KnownAgentState.COMPLETED, startedAt = 500, endedAt = 9_000, steps = 4, taskId = "srv")).single()
        assertEquals(SubagentState.DONE, done.state)
        assertEquals(listOf(500L, 8_500L, 4, "srv"), listOf(done.startedAt, done.durationMs, done.steps, done.taskId))
        val openEnded = merged(events, known("a", KnownAgentState.COMPLETED, startedAt = 500)).single()
        assertEquals(99_500L, openEnded.durationMs, "No end told: what the events computed")
        assertEquals(SubagentState.FAILED, merged(events, known("a", KnownAgentState.FAILED)).single().state)
        val running = merged(events, known("a", KnownAgentState.RUNNING, startedAt = 2_000, steps = 5)).single()
        assertEquals(SubagentState.RUNNING, running.state)
        assertEquals(listOf<Any>(1_000L, 99_000L, 5), listOf(running.startedAt, running.durationMs, running.steps), "Its own, earlier start counts; running ends now")
        assertEquals(SubagentState.RUNNING, merged(events, known("a", KnownAgentState.UNKNOWN)).single().state)
        val own = listOf(call(1, "a"), call(2, "i1", name = "Bash", parent = "a"), call(3, "i2", name = "Bash", parent = "a"))
        assertEquals(2, merged(own, known("a", KnownAgentState.RUNNING, steps = 1)).single().steps, "The larger count")
    }

    @Test fun aSubagentWithALiveTaskKeepsRunningWhateverTheListSays() {
        val live = merged(listOf(call(1, "a")), known("a", KnownAgentState.COMPLETED, taskId = "srv"), tasks = listOf(task("t1", "a", startedAt = 1_000))).single()
        assertEquals(SubagentState.RUNNING, live.state)
        assertEquals("t1", live.taskId)
    }

    @Test fun aFinishedSubagentStaysFinishedWhateverTheListSays() {
        val finished = listOf(call(1, "a"), result(2, "a"))
        val kept = merged(finished, known("a", KnownAgentState.FAILED, startedAt = 1_000, endedAt = 5_000)).single()
        assertEquals(SubagentState.DONE, kept.state)
        assertEquals(4_000L, kept.durationMs)
        assertEquals(1_000L, merged(finished, known("a", KnownAgentState.FAILED, startedAt = 1_000)).single().durationMs)
        val failed = listOf(call(1, "a"), result(2, "a", error = true))
        assertEquals(SubagentState.FAILED, merged(failed, known("a", KnownAgentState.COMPLETED)).single().state)
    }

    @Test fun aBackgroundSubagentTakesTheListsEndOrKeepsRunning() {
        val launched = listOf(background(1, "b"), result(2, "b"))
        val unsure = merged(launched, known("b", KnownAgentState.RUNNING)).single()
        assertEquals(SubagentState.BACKGROUND, unsure.state)
        assertEquals(99_000L, unsure.durationMs)
        val ended = merged(launched, known("b", KnownAgentState.COMPLETED, endedAt = 3_000)).single()
        assertEquals(SubagentState.DONE, ended.state)
        assertEquals(2_000L, ended.durationMs)
    }

    @Test fun aSubagentOfTheListThatNoLoadedEventShowsIsAddedInStartOrder() {
        val events = listOf(call(1, "a"))
        val tasks = listOf(task("t", "live"))
        val list = arrayOf(
            known("a", KnownAgentState.RUNNING),
            known("live", KnownAgentState.COMPLETED, startedAt = 6_000),
            known("done", KnownAgentState.COMPLETED, startedAt = 2_000, endedAt = 8_000, type = null),
            known("done2", KnownAgentState.COMPLETED, startedAt = 3_000),
            known("failed", KnownAgentState.FAILED, startedAt = 4_000, endedAt = 6_000, steps = 2),
            known("run", KnownAgentState.RUNNING, startedAt = 5_000, taskId = "srv"),
            known("unknown", KnownAgentState.UNKNOWN, startedAt = 7_000, endedAt = 7_500),
        )
        val agents = merged(events, *list, tasks = tasks)
        assertEquals(listOf("a", "done", "done2", "failed", "run", "live", "unknown"), agents.map { it.id })
        assertEquals(listOf(SubagentState.RUNNING, SubagentState.DONE, SubagentState.DONE, SubagentState.FAILED, SubagentState.RUNNING, SubagentState.RUNNING, SubagentState.STOPPED), agents.map { it.state })
        assertEquals("general-purpose", agents.of("done").type)
        assertEquals("Explore", agents.of("failed").type)
        assertEquals(6_000L, agents.of("done").durationMs)
        assertEquals(0L, agents.of("done2").durationMs)
        assertEquals(2_000L, agents.of("failed").durationMs)
        assertEquals(95_000L, agents.of("run").durationMs)
        assertEquals(listOf(2, "srv", "Known"), listOf(agents.of("failed").steps, agents.of("run").taskId, agents.of("run").description))
        assertEquals(500L, agents.of("unknown").durationMs)
        // A session that runs nothing has no running subagent; the runner reports no task.
        val idle = merged(events, *list, running = false)
        assertEquals(SubagentState.STOPPED, idle.of("run").state)
        assertEquals(SubagentState.DONE, idle.of("live").state)
    }

    @Test fun aListedSubagentsDepthFollowsTheParentsOfBothSources() {
        val agents = merged(
            listOf(call(1, "a")),
            known("child", KnownAgentState.RUNNING, startedAt = 2_000, parent = "a"),
            known("grand", KnownAgentState.RUNNING, startedAt = 3_000, parent = "child"),
            known("lost", KnownAgentState.RUNNING, startedAt = 4_000, parent = "ghost"),
            known("p", KnownAgentState.RUNNING, startedAt = 5_000, parent = "q"),
            known("q", KnownAgentState.RUNNING, startedAt = 6_000, parent = "p"),
        )
        assertEquals(listOf(0, 1, 2, 1, 16, 16), agents.map { it.depth })
        assertEquals("a", agents.of("child").parentId)
    }

    // The thread of a subagent, and the background commands.

    @Test fun aThreadHoldsOnlyTheEventsOfItsSubagentAtTheTopLevel() {
        val events = listOf(
            turn(1, TurnPhase.STARTED),
            call(2, "a"),
            call(3, "c1", name = "Bash", parent = "a"), result(4, "c1", parent = "a"), text(5, "Found it.", parent = "a"),
            call(6, "c2", name = "Bash", parent = "other"), result(7, "c2", parent = "other"), text(8, "Elsewhere.", parent = "other"),
            result(9, "a"),
        )
        val thread = threadEvents(events, "a")
        assertEquals(listOf(3L, 4L, 5L), thread.map { it.seq })
        assertTrue(thread.none { it is TranscriptEvent.ToolCall && it.parentToolUseId != null })
        assertTrue(thread.none { it is TranscriptEvent.ToolResult && it.parentToolUseId != null })
        assertTrue(thread.none { it is TranscriptEvent.AgentMessage && it.parentToolUseId != null })
        assertEquals(listOf(1L, 2L, 9L), threadEvents(events, null).map { it.seq })
    }

    @Test fun aBackgroundCommandWithoutACallOrWithAnUnloadedOneHasNoCommand() {
        val noCall = task("b1", null, type = "local_bash")
        val unloaded = task("b2", "tool-2", type = "local_bash")
        val agentTask = task("t1", "a1")
        // Nothing to look up: no task names a call.
        assertEquals(listOf(BackgroundCommand(noCall, null, false)), backgroundCommands(listOf(noCall), listOf(call(1, "x"))))
        // A call that is not loaded: no command either; a subagent's task is not a command.
        assertEquals(listOf(BackgroundCommand(noCall, null, false), BackgroundCommand(unloaded, null, false)), backgroundCommands(listOf(noCall, unloaded, agentTask), listOf(call(1, "x"))))
        assertEquals(emptyList(), backgroundCommands(listOf(agentTask), listOf(call(1, "a1"))))
        assertNull(backgroundCommands(listOf(unloaded), emptyList()).single().command)
    }
}
