// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion presentation/screens/MainScreen.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.presentation.screens

import androidx.compose.ui.graphics.vector.ImageVector
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Bell
import com.adamglin.phosphoricons.regular.FileText
import com.adamglin.phosphoricons.regular.FlowArrow
import com.adamglin.phosphoricons.regular.GearSix
import com.adamglin.phosphoricons.regular.Pulse
import com.adamglin.phosphoricons.regular.Receipt
import com.adamglin.phosphoricons.regular.TerminalWindow
import com.adamglin.phosphoricons.regular.Tray
import com.adamglin.phosphoricons.regular.Wallet
import org.jetbrains.compose.resources.StringResource
import run.ostrom.app.domain.models.Features
import run.ostrom.app.presentation.viewmodels.HomeUiState
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.tab_activity
import run.ostrom.app.resources.tab_artifacts
import run.ostrom.app.resources.tab_history
import run.ostrom.app.resources.tab_inbox
import run.ostrom.app.resources.tab_sessions
import run.ostrom.app.resources.tab_settings
import run.ostrom.app.resources.tab_spending
import run.ostrom.app.resources.tab_waits
import run.ostrom.app.resources.tab_workflows

/**
 * A tab of the signed-in app. [feature] is the capability its server must announce for the tab to show; null for the
 * tabs every server has.
 */
enum class MainTab(val feature: String?, val label: StringResource, val icon: ImageVector) {
    INBOX(Features.GATES, Res.string.tab_inbox, PhosphorIcons.Regular.Tray),
    WAITING(Features.WAITS, Res.string.tab_waits, PhosphorIcons.Regular.Bell),
    SESSIONS(Features.SESSIONS, Res.string.tab_sessions, PhosphorIcons.Regular.TerminalWindow),
    HISTORY(Features.RECEIPTS, Res.string.tab_history, PhosphorIcons.Regular.Receipt),
    SPENDING(Features.BUDGETS, Res.string.tab_spending, PhosphorIcons.Regular.Wallet),
    // Every server has its activity feed.
    ACTIVITY(null, Res.string.tab_activity, PhosphorIcons.Regular.Pulse),
    ARTIFACTS(Features.ARTIFACTS, Res.string.tab_artifacts, PhosphorIcons.Regular.FileText),
    WORKFLOWS(Features.WORKFLOWS, Res.string.tab_workflows, PhosphorIcons.Regular.FlowArrow),
    SETTINGS(null, Res.string.tab_settings, PhosphorIcons.Regular.GearSix),
}

/** The tabs the active account's server serves, in their order. */
internal fun HomeUiState.tabs(): List<MainTab> = MainTab.entries.filter { tab ->
    // The Sessions tab also holds the cloud sessions: it shows for a server with either.
    tab.feature == null || serves(tab.feature) || (tab == MainTab.SESSIONS && serves(Features.RUNNERS_CLOUD))
}

/** The tabs a phone's bar always offers first, when their server serves them; Settings comes last. */
private val PHONE_FIRST = listOf(MainTab.INBOX, MainTab.WAITING, MainTab.SESSIONS, MainTab.WORKFLOWS)

/** The most tabs a phone's bar shows, each with its label. */
internal const val MAX_PHONE_TABS = 5

/**
 * The phone's bar, as TeamCode's: at most [MAX_PHONE_TABS] tabs, every one labeled. The served ones of [PHONE_FIRST],
 * then the other served tabs in their order while there is room, and Settings last. The tabs left out ([phoneMoreTabs])
 * open from Settings.
 */
internal fun phoneTabs(tabs: List<MainTab>): List<MainTab> {
    val settings = listOfNotNull(MainTab.SETTINGS.takeIf { it in tabs })
    val others = PHONE_FIRST.filter { it in tabs } + tabs.filter { it !in PHONE_FIRST && it != MainTab.SETTINGS }
    return others.take(MAX_PHONE_TABS - settings.size) + settings
}

/** The served tabs a phone's bar has no room for, in their order: rows of Settings. */
internal fun phoneMoreTabs(tabs: List<MainTab>): List<MainTab> = phoneTabs(tabs).let { bar -> tabs.filter { it !in bar } }
