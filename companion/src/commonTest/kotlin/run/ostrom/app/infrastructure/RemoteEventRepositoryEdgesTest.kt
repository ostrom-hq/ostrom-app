// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure

import kotlinx.coroutines.test.runTest
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.OrgChange
import run.ostrom.app.infrastructure.repositories.RemoteEventRepository
import run.ostrom.app.infrastructure.stream.ReconnectPolicy
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The edges of the organization feed: a gap before any event, and a run event whose workflow is not a text. */
class RemoteEventRepositoryEdgesTest {
    private val server = ScriptedServer()
    private val repository = RemoteEventRepository(server.calls, ReconnectPolicy(firstDelayMillis = 10, random = Random(0)))

    private fun event(seq: Int, type: String, data: String) =
        "id: $seq\nevent: $type\ndata: {\"seq\":$seq,\"type\":\"$type\",\"subject\":\"workflow_run\",\"subjectId\":\"r$seq\",\"memberId\":\"m1\",\"at\":\"2026-11-14T10:00:00Z\",\"data\":$data}\n\n"

    private suspend fun collect(types: Set<String>): List<LiveUpdate<OrgChange>> {
        val updates = mutableListOf<LiveUpdate<OrgChange>>()
        assertFailsWith<AppException.Unauthorized> { repository.changes("a1", types).collect { updates += it } }
        return updates
    }

    @Test fun aGapBeforeAnyEventIsReportedAndTheFeedGoesOn() = runTest {
        server.signedIn()
        server.stream("event: gap\ndata: {\"oldestSeq\":9}\n\n" + event(9, OrgChange.RUN_UPDATED, "{\"workflowId\":\"wf1\"}") + "event: denied\ndata: {}\n\n")
        assertEquals(
            listOf(LiveUpdate.Opened(resumed = false), LiveUpdate.Gap, LiveUpdate.Item(OrgChange(OrgChange.RUN_UPDATED, "r9", "m1", "wf1"))),
            collect(setOf(OrgChange.RUN_UPDATED)),
        )
    }

    @Test fun aWorkflowThatIsNotATextIsLeftOutWithoutFailingTheFeed() = runTest {
        server.signedIn()
        server.stream(
            event(1, OrgChange.RUN_UPDATED, "{\"workflowId\":5}") + event(2, OrgChange.RUN_UPDATED, "{\"workflowId\":{\"id\":\"wf\"}}") +
                event(3, OrgChange.RUN_UPDATED, "{\"status\":\"running\"}") + "event: denied\ndata: {}\n\n",
        )
        assertEquals(
            listOf(LiveUpdate.Opened(resumed = false)) + (1..3).map { LiveUpdate.Item(OrgChange(OrgChange.RUN_UPDATED, "r$it", "m1", null)) },
            collect(setOf(OrgChange.RUN_UPDATED)),
        )
    }
}
