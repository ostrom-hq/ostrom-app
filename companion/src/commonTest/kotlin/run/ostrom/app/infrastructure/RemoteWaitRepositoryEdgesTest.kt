// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import run.ostrom.app.domain.models.WaitStatus
import run.ostrom.app.infrastructure.repositories.RemoteWaitRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The order of the waits in progress, and a cancelled wait. */
class RemoteWaitRepositoryEdgesTest {
    private val server = ScriptedServer()

    private fun wait(id: String, status: String = "open", resolver: String = "time", extra: String = "") =
        """{"id":"$id","kind":"question","status":"$status","assignee":"ada","createdBy":"grace","createdAt":"2026-11-13T10:00:00Z","resolver":"$resolver",
        "question":{"title":"t","fields":[],"actions":[],"refs":[]}$extra}"""

    @Test fun theWaitsInProgressAreOrderedByWhenTheyAreDueThenById() = runTest {
        server.signedIn()
        server.json("""{"items":[${wait("z", extra = ""","dueAt":"2026-11-13T12:00:00Z"""")},${wait("m")},${wait("b", extra = ""","dueAt":"2026-11-13T12:00:00Z"""")}]}""")
        server.json("""{"items":[${wait("a", resolver = "event", extra = ""","timeoutAt":"2026-11-13T11:00:00Z"""")},${wait("c", resolver = "event")}]}""")
        // Due first, the earliest before the later, equal ones by id, those without a time last (by id too).
        assertEquals(listOf("a", "b", "z", "c", "m"), RemoteWaitRepository(server.calls).inProgress("a1").map { it.id })
    }

    @Test fun aCancelledWaitIsClosedAndCancelledWhileAnOpenOneIsNeither() = runTest {
        server.signedIn()
        server.json("""{"items":[${wait("w1", status = "cancelled")},${wait("w2")},${wait("w3", status = "resolved")}]}""")
        val (cancelled, open, resolved) = RemoteWaitRepository(server.calls).list("a1", WaitStatus.OPEN).waits
        assertTrue(cancelled.cancelled && !cancelled.open)
        assertTrue(open.open && !open.cancelled)
        assertFalse(resolved.open || resolved.cancelled)
    }
}
