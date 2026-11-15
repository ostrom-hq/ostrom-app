// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/components/SessionComponents.kt; see NOTICE.
package run.ostrom.app.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.domain.models.SessionHost
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.session_host_offline_chip
import run.ostrom.app.resources.session_host_refused
import run.ostrom.app.resources.session_host_refused_chip
import run.ostrom.app.resources.session_host_removed
import run.ostrom.app.resources.session_host_unnamed
import run.ostrom.app.resources.session_kind_cloud
import run.ostrom.app.resources.session_kind_local
import run.ostrom.app.resources.session_kind_remote
import run.ostrom.app.resources.session_refusal_agent_unsupported
import run.ostrom.app.resources.session_refusal_folder_mismatch
import run.ostrom.app.resources.session_refusal_folder_missing
import run.ostrom.app.resources.session_refusal_folder_moved
import run.ostrom.app.resources.session_refusal_folder_not_configured
import run.ostrom.app.resources.session_refusal_folder_refused
import run.ostrom.app.resources.session_refusal_host_failed
import run.ostrom.app.resources.session_refusal_none
import run.ostrom.app.resources.session_refusal_resume_not_found
import run.ostrom.app.resources.session_refusal_unknown
import run.ostrom.app.resources.session_waiting_host_chip

/** A remote session's device as the member knows it: its name, or that it was removed or never named. */
@Composable
fun hostName(host: SessionHost?): String = when {
    host == null || host.removed -> stringResource(Res.string.session_host_removed)
    else -> host.deviceName?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.session_host_unnamed)
}

/** The system a device reports, worded as its vendor does; null until the device reported it. */
fun systemOf(host: SessionHost): String? = systemOf(host.platform, host.osVersion)

fun systemOf(platform: String?, osVersion: String?): String? {
    val name = when (platform) {
        null -> return null
        "macos" -> "macOS"
        "windows" -> "Windows"
        "linux" -> "Linux"
        "ios" -> "iOS"
        "android" -> "Android"
        else -> platform
    }
    return listOfNotNull(name, osVersion?.takeIf { it.isNotBlank() }).joinToString(" ")
}

/**
 * Where the session runs: "Cloud", "Remote · <device>" or the local run; a device that stopped answering is marked
 * offline next to it, one that did not start the session yet or refused it too.
 */
@Composable
fun KindChips(session: CloudSession, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        val text = when {
            session.remote -> stringResource(Res.string.session_kind_remote, hostName(session.host))
            session.local -> stringResource(Res.string.session_kind_local)
            else -> stringResource(Res.string.session_kind_cloud)
        }
        Chip(text, Modifier.testTag("where-${session.id}"), tone = Tone.MUTED)
        if (session.hostOffline) Chip(stringResource(Res.string.session_host_offline_chip), Modifier.testTag("offline-${session.id}"), tone = Tone.WARN)
        if (session.waitingForHost) Chip(stringResource(Res.string.session_waiting_host_chip), Modifier.testTag("waiting-host-${session.id}"), tone = Tone.ACCENT)
        if (session.hostRefused) Chip(stringResource(Res.string.session_host_refused_chip), Modifier.testTag("host-refused-${session.id}"), tone = Tone.DANGER)
    }
}

/** Why the device refused to start the session, worded for each reason its daemon gives. */
@Composable
fun refusalText(session: CloudSession): String {
    val reason = session.refusalReason
    val why = when (reason) {
        "folder_not_configured" -> stringResource(Res.string.session_refusal_folder_not_configured)
        "folder_missing" -> stringResource(Res.string.session_refusal_folder_missing)
        "folder_moved" -> stringResource(Res.string.session_refusal_folder_moved)
        "folder_refused" -> stringResource(Res.string.session_refusal_folder_refused)
        "folder_mismatch" -> stringResource(Res.string.session_refusal_folder_mismatch)
        "agent_unsupported" -> stringResource(Res.string.session_refusal_agent_unsupported)
        "host_failed" -> stringResource(Res.string.session_refusal_host_failed)
        "resume_not_found" -> stringResource(Res.string.session_refusal_resume_not_found)
        null -> stringResource(Res.string.session_refusal_none)
        else -> stringResource(Res.string.session_refusal_unknown, reason)
    }
    return stringResource(Res.string.session_host_refused, hostName(session.host), why)
}
