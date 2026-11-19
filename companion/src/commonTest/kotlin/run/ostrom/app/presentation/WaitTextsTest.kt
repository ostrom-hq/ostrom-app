// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import run.ostrom.app.domain.models.FieldType
import run.ostrom.app.domain.models.Wait
import run.ostrom.app.domain.models.WaitAction
import run.ostrom.app.domain.models.WaitField
import run.ostrom.app.domain.models.WaitKind
import run.ostrom.app.domain.models.WaitSource
import run.ostrom.app.presentation.screens.BLOCKED_PREFIX
import run.ostrom.app.presentation.screens.DROP_TITLE
import run.ostrom.app.presentation.screens.ServerWait
import run.ostrom.app.presentation.screens.serverWait
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which waits are the server's own, written in English and shown in the app's language. */
class WaitTextsTest {
    private fun wait(
        source: WaitSource = WaitSource.WAIT,
        kind: WaitKind = WaitKind.HUMAN_REQUEST,
        title: String = "Review the plan",
        fields: List<WaitField> = emptyList(),
        actions: List<String> = emptyList(),
    ) = Wait("w1", source, kind, true, "s1", "Fix the login", null, "m1", title, null, emptyList(), fields, actions.map { WaitAction(it, it.replaceFirstChar(Char::uppercase)) }, 1)

    @Test fun aSessionsOwnPermissionRequestAndQuestionAreTheServers() {
        assertEquals(ServerWait.PERMISSION, serverWait(wait(source = WaitSource.SESSION, kind = WaitKind.PERMISSION)))
        assertEquals(ServerWait.QUESTION, serverWait(wait(source = WaitSource.SESSION, kind = WaitKind.QUESTION)))
        assertNull(serverWait(wait(source = WaitSource.SESSION, kind = WaitKind.HUMAN_REQUEST)))
        assertNull(serverWait(wait(source = WaitSource.SESSION, kind = WaitKind.TURN_FAILED)))
    }

    @Test fun onlyAPlainHumanRequestOfTheWaitsTableCanBeATodoWait() {
        assertNull(serverWait(wait(kind = WaitKind.PERMISSION)))
        assertNull(serverWait(wait(kind = WaitKind.TIMED)))
        val field = WaitField("f", FieldType.TEXT, "F")
        assertNull(serverWait(wait(title = DROP_TITLE, fields = listOf(field), actions = listOf("yes", "no"))), "a wait with fields is an agent's own")
        assertNull(serverWait(wait()), "a wait written by an agent is shown as it was written")
    }

    @Test fun theDropItemWaitNeedsItsTitleAndExactlyYesAndNo() {
        assertEquals(ServerWait.DROP_ITEM, serverWait(wait(title = DROP_TITLE, actions = listOf("yes", "no"))))
        assertNull(serverWait(wait(title = DROP_TITLE, actions = listOf("no", "yes"))))
        assertNull(serverWait(wait(title = DROP_TITLE, actions = listOf("yes"))))
        assertNull(serverWait(wait(title = "Drop this item?", actions = listOf("yes", "no"))))
    }

    @Test fun theBlockedWaitNeedsItsPrefixAndTheStopAndNoActions() {
        assertEquals(ServerWait.BLOCKED, serverWait(wait(title = "${BLOCKED_PREFIX}the todo", actions = listOf("yes", "stop", "no"))))
        assertEquals(ServerWait.BLOCKED, serverWait(wait(title = "${BLOCKED_PREFIX}Fix it", actions = listOf("stop", "no"))))
        assertNull(serverWait(wait(title = "${BLOCKED_PREFIX}Fix it", actions = listOf("no"))), "no stop")
        assertNull(serverWait(wait(title = "${BLOCKED_PREFIX}Fix it", actions = listOf("stop"))), "no resume")
        assertNull(serverWait(wait(title = "Stuck: Fix it", actions = listOf("stop", "no"))), "not the server's title")
    }
}
