// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure

import kotlinx.coroutines.test.runTest
import run.ostrom.app.infrastructure.repositories.RemoteSessionRepository
import kotlin.test.Test
import kotlin.test.assertEquals

/** When a session is shown as lost: the server says so, or marks it ended without an end time. */
class RemoteSessionRepositoryEdgesTest {
    private val server = ScriptedServer()

    private fun session(id: String, extra: String) = """{"id":"$id","agent":"claude-code","dev":"ada","startedAt":"2026-11-14T09:00:00Z"$extra}"""

    @Test fun aSessionIsLostWhenFlaggedOrEndedWithoutAnEndTime() = runTest {
        server.signedIn()
        server.json(
            """{"items":[${session("flagged", ""","lost":true""")},${session("ended-unknown", ""","ended":true""")},
            ${session("ended-known", ""","ended":true,"endedAt":"2026-11-14T10:00:00Z"""")},${session("not-ended", ""","ended":false,"lost":false""")},
            ${session("open", "")}]}""",
        )
        val sessions = RemoteSessionRepository(server.calls).sessions("a1", open = null, before = null).sessions
        assertEquals(listOf("flagged" to true, "ended-unknown" to true, "ended-known" to false, "not-ended" to false, "open" to false), sessions.map { it.id to it.lost })
    }
}
