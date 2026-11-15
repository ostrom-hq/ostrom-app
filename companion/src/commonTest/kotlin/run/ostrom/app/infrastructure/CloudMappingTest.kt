// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../infrastructure/CloudMappingLiveTextTest.kt; see NOTICE.
package run.ostrom.app.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import run.ostrom.app.domain.models.BackgroundTask
import run.ostrom.app.domain.models.KnownAgent
import run.ostrom.app.domain.models.KnownAgentState
import run.ostrom.app.domain.models.TranscriptEvent
import run.ostrom.app.infrastructure.repositories.domain
import run.ostrom.app.presentation.transcript.transcriptItems
import run.ostrom.core.api.models.CloudEvent
import run.ostrom.core.api.models.CloudEventPage
import run.ostrom.core.api.models.CloudEventTypes
import run.ostrom.core.api.models.CloudSubagent

/** The app shows final messages only: live text deltas are ignored, and the final message still shows. */
class CloudMappingTest {
    private fun iso(millis: Long) = Instant.fromEpochMilliseconds(millis).toString()

    private fun event(seq: Long, type: String, at: Long, data: JsonObject, feed: String? = null) =
        CloudEvent(seq, type, iso(at), 1, data, feed)

    @Test
    fun liveTextDeltasAreIgnoredAndTheFinalMessageShows() {
        val delta = event(3, CloudEventTypes.AGENT_MESSAGE_DELTA, 3, buildJsonObject { put("messageId", "msg_1"); put("index", 0); put("text", "Hal") }).domain()
        assertIs<TranscriptEvent.Unknown>(delta)
        val final = event(4, CloudEventTypes.AGENT_MESSAGE, 4, buildJsonObject { put("text", "Half done."); put("messageId", "msg_1"); put("index", 0) }).domain()
        assertEquals(TranscriptEvent.AgentMessage(4, 4, "Half done.", null), final)
        assertEquals(listOf("Agent"), transcriptItems(listOf(delta, final)).map { it::class.simpleName })
    }

    @Test
    fun aTasksEventListsTheLiveBackgroundTasks() {
        val event = event(7, CloudEventTypes.TASKS, 7_000, buildJsonObject {
            putJsonArray("tasks") {
                addJsonObject { put("id", "b1"); put("type", "local_bash"); put("description", "npm test"); put("toolUseId", "toolu_9"); put("startedAt", 5_000) }
                addJsonObject { put("id", "a1"); put("type", "local_agent"); put("description", "Review"); put("subagentType", "Explore") }
                addJsonObject { put("description", "no id: left out") }
            }
            put("stoppable", true)
        }).domain()
        val tasks = assertIs<TranscriptEvent.Tasks>(event)
        assertTrue(tasks.stoppable)
        assertEquals(listOf(BackgroundTask("b1", "local_bash", "npm test", null, "toolu_9", 5_000), BackgroundTask("a1", "local_agent", "Review", "Explore", null, null)), tasks.tasks)
        assertTrue(transcriptItems(listOf(event)).isEmpty(), "The panel shows it, not the transcript")
        assertFalse(assertIs<TranscriptEvent.Tasks>(event(8, CloudEventTypes.TASKS, 8_000, buildJsonObject { }).domain()).stoppable)
    }

    @Test
    fun anIsoTimeReadsAsEpochMillisAndAnUnreadableOneAsZero() {
        assertEquals(1_700_000_000_000, event(1, CloudEventTypes.AGENT_MESSAGE, 1_700_000_000_000, buildJsonObject { put("text", "Hi") }).domain().at)
        assertEquals(0, CloudEvent(2, CloudEventTypes.AGENT_MESSAGE, "yesterday", 1, buildJsonObject { put("text", "Hi") }).domain().at)
    }

    @Test
    fun aSubagentEventWithoutItsParentInItsDataTakesItsFeed() {
        val inFeed = event(5, CloudEventTypes.TOOL_CALL, 5, buildJsonObject { put("id", "t1"); put("name", "Read"); putJsonObject("input") { put("file_path", "a.kt") } }, feed = "toolu_agent")
        assertEquals("toolu_agent", assertIs<TranscriptEvent.ToolCall>(inFeed.domain()).parentToolUseId)
        val named = event(6, CloudEventTypes.AGENT_MESSAGE, 6, buildJsonObject { put("text", "Found it."); put("parentToolUseId", "toolu_other") }, feed = "toolu_agent")
        assertEquals("toolu_other", assertIs<TranscriptEvent.AgentMessage>(named.domain()).parentToolUseId)
    }

    @Test
    fun aPageKeepsItsCursorsAndFallsBackToItsLastEvent() {
        val events = listOf(event(9, CloudEventTypes.AGENT_MESSAGE, 9, buildJsonObject { put("text", "Hi") }))
        val page = CloudEventPage(events, firstSeq = 1, lastSeq = 12, nextBefore = 9).domain()
        assertEquals(9L, page.nextBefore)
        assertEquals(12L, page.lastSeq)
        assertEquals(9L, CloudEventPage(events).domain().lastSeq)
        assertEquals(0L, CloudEventPage(emptyList()).domain().lastSeq)
    }

    @Test
    fun aSubagentReadsItsStateAndTimes() {
        val agent = CloudSubagent("toolu_a", "Explore", "Map the code", "running", iso(1_000), null, 3, null, "task-1").domain()
        assertEquals(KnownAgent("toolu_a", "Explore", "Map the code", KnownAgentState.RUNNING, 1_000, null, 3, null, "task-1"), agent)
        val done = CloudSubagent("toolu_b", " ", state = "something.new", startedAt = "?", endedAt = iso(2_000)).domain()
        assertEquals(null, done.type)
        assertEquals(KnownAgentState.UNKNOWN, done.state)
        assertEquals(0L, done.startedAt)
        assertEquals(2_000L, done.endedAt)
        assertEquals(KnownAgentState.COMPLETED, CloudSubagent("c", state = "completed", startedAt = iso(1)).domain().state)
        assertEquals(KnownAgentState.FAILED, CloudSubagent("f", state = "failed", startedAt = iso(1)).domain().state)
    }
}
