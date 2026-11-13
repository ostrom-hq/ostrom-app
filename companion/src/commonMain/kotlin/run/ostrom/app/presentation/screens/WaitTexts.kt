// SPDX-License-Identifier: AGPL-3.0-only
// Ported from TeamCode (Apache-2.0), companion/.../presentation/screens/WaitTexts.kt; see NOTICE.
package run.ostrom.app.presentation.screens

import androidx.compose.runtime.Composable
import run.ostrom.app.domain.models.Wait
import run.ostrom.app.domain.models.WaitAction
import run.ostrom.app.domain.models.WaitKind
import run.ostrom.app.domain.models.WaitSource
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.waits_action_drop_it
import run.ostrom.app.resources.waits_action_drop_item
import run.ostrom.app.resources.waits_action_keep_it
import run.ostrom.app.resources.waits_action_resume
import run.ostrom.app.resources.waits_action_stop_here
import run.ostrom.app.resources.waits_title_blocked
import run.ostrom.app.resources.waits_title_blocked_todo
import run.ostrom.app.resources.waits_title_drop
import run.ostrom.app.resources.waits_title_permission
import run.ostrom.app.resources.waits_title_question
import org.jetbrains.compose.resources.stringResource

/**
 * The waits the server writes itself, in English: a session's open permission request or question, and the two todo waits
 * (`WaitBackedDropWaits`). Their title and buttons are shown in the app's language; every other wait, written by an agent or a
 * person, is shown as it was written and never translated.
 */
internal enum class ServerWait { PERMISSION, QUESTION, DROP_ITEM, BLOCKED }

internal const val DROP_TITLE = "Drop this item of the todo?"
internal const val BLOCKED_PREFIX = "Blocked: "
internal const val BLOCKED_NO_ITEM = "the todo"

internal fun serverWait(wait: Wait): ServerWait? {
    if (wait.source == WaitSource.SESSION) return when (wait.kind) {
        WaitKind.PERMISSION -> ServerWait.PERMISSION
        WaitKind.QUESTION -> ServerWait.QUESTION
        else -> null
    }
    if (wait.kind != WaitKind.HUMAN_REQUEST || wait.fields.isNotEmpty()) return null
    val actions = wait.actions.map { it.id }
    return when {
        wait.title == DROP_TITLE && actions == listOf("yes", "no") -> ServerWait.DROP_ITEM
        wait.title.startsWith(BLOCKED_PREFIX) && "stop" in actions && "no" in actions -> ServerWait.BLOCKED
        else -> null
    }
}

@Composable
internal fun waitTitle(wait: Wait): String = when (serverWait(wait)) {
    ServerWait.PERMISSION -> stringResource(Res.string.waits_title_permission)
    ServerWait.QUESTION -> stringResource(Res.string.waits_title_question)
    ServerWait.DROP_ITEM -> stringResource(Res.string.waits_title_drop)
    ServerWait.BLOCKED -> wait.title.removePrefix(BLOCKED_PREFIX).let {
        if (it == BLOCKED_NO_ITEM) stringResource(Res.string.waits_title_blocked_todo) else stringResource(Res.string.waits_title_blocked, it)
    }
    null -> wait.title
}

@Composable
internal fun waitActionLabel(wait: Wait, action: WaitAction): String = when (serverWait(wait) to action.id) {
    ServerWait.DROP_ITEM to "yes" -> stringResource(Res.string.waits_action_drop_it)
    ServerWait.DROP_ITEM to "no" -> stringResource(Res.string.waits_action_keep_it)
    ServerWait.BLOCKED to "no" -> stringResource(Res.string.waits_action_resume)
    ServerWait.BLOCKED to "yes" -> stringResource(Res.string.waits_action_drop_item)
    ServerWait.BLOCKED to "stop" -> stringResource(Res.string.waits_action_stop_here)
    else -> action.label
}
