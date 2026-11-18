// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/screens/SubagentPanel.kt; see NOTICE.
package run.ostrom.app.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import run.ostrom.app.presentation.transcript.BackgroundCommand
import run.ostrom.app.presentation.transcript.LiveTasks
import run.ostrom.app.presentation.transcript.Subagent

/**
 * Where the panel of what runs in a session goes, by Material's window size classes, the breakpoints the left sidebar
 * already uses ([isWideWindow][run.ostrom.app.presentation.components.isWideWindow]).
 */
enum class SubagentPanelMode {
    /** Compact width, or shorter than medium (a phone, in either orientation): a screen of its own, entered and left. */
    SCREEN,
    /** Medium and expanded (600 to 1199 dp): the sidebar already takes a column; the panel slides over the conversation. */
    OVERLAY,
    /** Large (1200 to 1599 dp): a column beside the conversation. */
    SIDE,
    /** Extra-large (1600 dp and more): beside it too, and an open subagent gets its own column next to the main thread. */
    SIDE_AND_THREAD,
}

enum class PanelPlacement { NONE, SCREEN, OVERLAY, SIDE }

/** What shows: the panel's [panel] placement, and an open subagent [threadBeside] the main thread or [threadReplacesMain]. */
data class SubagentLayout(val panel: PanelPlacement, val threadBeside: Boolean, val threadReplacesMain: Boolean)

fun subagentPanelMode(widthDp: Int, heightDp: Int): SubagentPanelMode = when {
    widthDp < WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND || heightDp < WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND -> SubagentPanelMode.SCREEN
    widthDp < WindowSizeClass.WIDTH_DP_LARGE_LOWER_BOUND -> SubagentPanelMode.OVERLAY
    widthDp < WindowSizeClass.WIDTH_DP_EXTRA_LARGE_LOWER_BOUND -> SubagentPanelMode.SIDE
    else -> SubagentPanelMode.SIDE_AND_THREAD
}

/**
 * The layout for [mode]. Nothing shows without subagents, commands or wake-ups ([hasContent]). A screen or an overlay
 * opens on demand ([panelOpen]); a side panel shows unless the member hid it ([panelHidden]). The open subagent and both
 * flags live in the view model, so a resize only moves them: narrowed from a side panel, the panel folds away rather
 * than covering the conversation unasked, and the open subagent stays open.
 */
fun subagentLayout(mode: SubagentPanelMode, hasContent: Boolean, panelOpen: Boolean, panelHidden: Boolean, threadOpen: Boolean): SubagentLayout {
    if (!hasContent) return SubagentLayout(PanelPlacement.NONE, threadBeside = false, threadReplacesMain = false)
    val panel = when (mode) {
        SubagentPanelMode.SCREEN -> if (panelOpen) PanelPlacement.SCREEN else PanelPlacement.NONE
        SubagentPanelMode.OVERLAY -> if (panelOpen) PanelPlacement.OVERLAY else PanelPlacement.NONE
        SubagentPanelMode.SIDE, SubagentPanelMode.SIDE_AND_THREAD -> if (panelHidden) PanelPlacement.NONE else PanelPlacement.SIDE
    }
    val beside = threadOpen && mode == SubagentPanelMode.SIDE_AND_THREAD
    return SubagentLayout(panel, threadBeside = beside, threadReplacesMain = threadOpen && !beside)
}

/** Everything the panel lists, derived from the transcript ([subagentsOf][run.ostrom.app.presentation.transcript.subagentsOf]). */
data class RunningWork(val subagents: List<Subagent>, val live: LiveTasks?, val commands: List<BackgroundCommand>) {
    val scheduled: Int get() = live?.scheduled ?: 0
    val hasContent: Boolean get() = subagents.isNotEmpty() || commands.isNotEmpty() || scheduled > 0
    /** What runs now: active subagents, background commands and monitors, scheduled wake-ups. */
    val running: Int get() = subagents.count { it.active } + commands.size + scheduled
}
