// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure

import kotlinx.coroutines.test.runTest
import run.ostrom.app.infrastructure.repositories.RemoteApprovalRepository
import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteApprovalRepositoryEdgesTest {
    private val server = ScriptedServer()

    @Test fun aRequestWithoutCommitsShowsNone() = runTest {
        server.signedIn()
        server.json(
            """{"approval":{"id":"appr_1","title":"Deploy","requestedFrom":"ada","channel":"app","status":"approved","shownDigest":"d","evidence":[]},
            "who":{"dev":"grace"},"what":{"action":"deploy"},"askedVia":[],"you":{"login":"ada","self":false,"canApprove":true}}""",
        )
        val approval = RemoteApprovalRepository(server.calls).view("a1", "appr_1")
        assertEquals(emptyList(), approval.commits)
        assertEquals(emptyList(), approval.askedBy)
    }
}
