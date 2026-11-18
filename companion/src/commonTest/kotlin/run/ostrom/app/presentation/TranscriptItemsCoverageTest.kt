// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import run.ostrom.app.domain.models.TranscriptEvent
import run.ostrom.app.domain.models.TurnPhase
import run.ostrom.app.presentation.transcript.TranscriptItem
import run.ostrom.app.presentation.transcript.indexOfEvent
import run.ostrom.app.presentation.transcript.transcriptItems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** What each item of the transcript list reports of the events it shows, and the events the list builder pairs, skips or shows alone. */
class TranscriptItemsCoverageTest {
    private fun call(seq: Long, id: String, name: String = "Bash", parent: String? = null) = TranscriptEvent.ToolCall(seq, seq, id, name, "{}", "ls", parent)
    private fun result(seq: Long, id: String, parent: String? = null) = TranscriptEvent.ToolResult(seq, seq, id, "ok", false, null, parent)
    private fun agentMessage(seq: Long, parent: String? = null) = TranscriptEvent.AgentMessage(seq, seq, "Text", parent)

    @Test fun everyItemReportsItsKeyItsNestingAndItsSeqs() {
        val user = TranscriptItem.User(TranscriptEvent.UserMessage(1, 1, "Go", "i1", "m1"))
        assertEquals(1L, user.key)
        assertFalse(user.nested)
        assertEquals(listOf(1L), user.seqs)
        val agent = TranscriptItem.Agent(agentMessage(2))
        assertEquals(listOf(2L), agent.seqs)
        assertFalse(agent.nested)
        assertTrue(TranscriptItem.Agent(agentMessage(3, parent = "a")).nested)
        val end = TranscriptItem.TurnEnd(TranscriptEvent.Turn(4, 4, TurnPhase.COMPLETED, null, null))
        assertEquals(4L, end.key)
        assertFalse(end.nested)
        assertEquals(listOf(4L), end.seqs)
        val system = TranscriptItem.System(TranscriptEvent.Error(5, 5, "code", "message"))
        assertEquals(5L, system.key)
        assertFalse(system.nested)
        assertEquals(listOf(5L), system.seqs)
    }

    @Test fun aToolItemIsKeyedByItsCallElseItsResultAndNestedByWhicheverHasAParent() {
        val both = TranscriptItem.Tool(call(1, "a"), result(2, "a"))
        assertEquals(1L, both.key)
        assertEquals(listOf(1L, 2L), both.seqs)
        assertFalse(both.nested)
        val alone = TranscriptItem.Tool(null, result(7, "old"))
        assertEquals(7L, alone.key)
        assertEquals(listOf(7L), alone.seqs)
        assertFalse(alone.nested)
        assertTrue(TranscriptItem.Tool(null, result(8, "old", parent = "p")).nested)
        assertTrue(TranscriptItem.Tool(call(9, "b", parent = "p"), null).nested)
        assertEquals(listOf(9L), TranscriptItem.Tool(call(9, "b", parent = "p"), null).seqs)
        assertTrue(TranscriptItem.Tool(call(10, "c"), result(11, "c", parent = "p")).nested, "The result's parent counts when the call has none")
    }

    @Test fun aFoldedRunReportsItsFirstToolsKeyAndNestingAndAllItsSeqs() {
        val tools = TranscriptItem.Tools(listOf(TranscriptItem.Tool(call(3, "a", parent = "p"), result(4, "a", parent = "p")), TranscriptItem.Tool(call(5, "b", parent = "p"), null)))
        assertEquals(3L, tools.key)
        assertTrue(tools.nested)
        assertEquals(listOf(3L, 4L, 5L), tools.seqs)
        assertFalse(TranscriptItem.Tools(listOf(TranscriptItem.Tool(call(1, "a"), null))).nested)
    }

    @Test fun aSubagentCardIsKeyedByItsCallAndShowsItsResultsSeq() {
        val card = TranscriptItem.Subagent(call(6, "a", name = "Agent", parent = "p"), null)
        assertEquals(6L, card.key)
        assertTrue(card.nested)
        assertEquals(listOf(6L), card.seqs)
        val answered = TranscriptItem.Subagent(call(7, "b", name = "Agent"), result(8, "b"))
        assertFalse(answered.nested)
        assertEquals(listOf(7L, 8L), answered.seqs)
    }

    @Test fun theReadingAnchorFindsTheItemThatShowsAnEvent() {
        val items = transcriptItems(listOf(call(1, "a"), result(2, "a"), call(3, "b"), result(4, "b"), agentMessage(5)))
        assertEquals(0, items.indexOfEvent(2))
        assertEquals(0, items.indexOfEvent(3), "A folded run shows each of its events")
        assertEquals(1, items.indexOfEvent(5))
        assertEquals(-1, items.indexOfEvent(99))
        assertEquals(-1, emptyList<TranscriptItem>().indexOfEvent(1))
    }

    @Test fun anAgentCallWithoutAnIdIsAnOrdinaryToolAndItsIdIsNotPaired() {
        val items = transcriptItems(listOf(call(1, "", name = "Agent"), result(2, ""), call(3, "", name = "Bash"), call(4, "x", name = "Task"), result(5, "x")))
        assertEquals(2, items.size)
        val tools = assertIs<TranscriptItem.Tools>(items[0]).tools
        assertEquals(listOf(1L, null, 3L), tools.map { it.call?.seq })
        assertEquals(listOf(null, 2L, null), tools.map { it.result?.seq }, "A call with no id is not paired, a result with no id stands alone")
        val card = assertIs<TranscriptItem.Subagent>(items[1])
        assertEquals("x", card.call.id)
        assertEquals(5L, card.result?.seq)
    }

    @Test fun onlyRejectedAndCancelledInputsAreShown() {
        val items = transcriptItems(
            listOf(
                TranscriptEvent.InputChanged(1, 1, "i1", "rejected", null),
                TranscriptEvent.InputChanged(2, 2, "i2", "cancelled", null),
                TranscriptEvent.InputChanged(3, 3, "i3", "consumed", null),
                TranscriptEvent.InputChanged(4, 4, "i4", null, null),
            ),
        )
        assertEquals(listOf(1L, 2L), items.map { it.key })
        assertTrue(items.all { it is TranscriptItem.System })
    }
}
