// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion presentation/screens/MainScreen.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.presentation.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Bell
import com.adamglin.phosphoricons.regular.FileText
import com.adamglin.phosphoricons.regular.GearSix
import com.adamglin.phosphoricons.regular.Tray
import com.adamglin.phosphoricons.regular.Pulse
import com.adamglin.phosphoricons.regular.Receipt
import com.adamglin.phosphoricons.regular.Wallet
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.Features
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.Banner
import run.ostrom.app.presentation.components.DemoBanner
import run.ostrom.app.presentation.components.LocalWideLayout
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.components.underDemoBanner
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.viewmodels.HomeUiState
import run.ostrom.app.presentation.viewmodels.HomeViewModel
import run.ostrom.app.presentation.viewmodels.WaitsUiState
import run.ostrom.app.presentation.viewmodels.WaitsViewModel
import run.ostrom.app.presentation.viewmodels.words
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.home_signed_out
import run.ostrom.app.resources.home_sign_in_again
import run.ostrom.app.resources.tab_inbox
import run.ostrom.app.resources.tab_activity
import run.ostrom.app.resources.tab_artifacts
import run.ostrom.app.resources.tab_history
import run.ostrom.app.resources.tab_settings
import run.ostrom.app.resources.tab_spending
import run.ostrom.app.resources.tab_waits

/**
 * A tab of the signed-in app. [feature] is the capability its server must announce for the tab to show; null for the
 * tabs every server has.
 */
enum class MainTab(val feature: String?, val label: StringResource, val icon: ImageVector) {
    INBOX(Features.GATES, Res.string.tab_inbox, PhosphorIcons.Regular.Tray),
    WAITING(Features.WAITS, Res.string.tab_waits, PhosphorIcons.Regular.Bell),
    HISTORY(Features.RECEIPTS, Res.string.tab_history, PhosphorIcons.Regular.Receipt),
    SPENDING(Features.BUDGETS, Res.string.tab_spending, PhosphorIcons.Regular.Wallet),
    // Every server has its activity feed.
    ACTIVITY(null, Res.string.tab_activity, PhosphorIcons.Regular.Pulse),
    ARTIFACTS(Features.ARTIFACTS, Res.string.tab_artifacts, PhosphorIcons.Regular.FileText),
    SETTINGS(null, Res.string.tab_settings, PhosphorIcons.Regular.GearSix),
}

/** The tabs the active account's server serves, in their order. */
internal fun HomeUiState.tabs(): List<MainTab> = MainTab.entries.filter { tab -> tab.feature == null || serves(tab.feature) }

/**
 * The signed-in app: the tabs at the bottom on a phone, a rail on a wide window. A server that speaks another API
 * version replaces everything with [IncompatibleServerScreen]; a session the server ended asks to sign in again.
 */
@Composable
fun MainScreen(
    onAddAccount: () -> Unit,
    onSignInAgain: (Account) -> Unit,
    onOpenRequest: (accountId: String, id: String) -> Unit = { _, _ -> },
    onOpenNotifications: (accountId: String) -> Unit = {},
    onOpenArtifact: (accountId: String, artifactId: String) -> Unit = { _, _ -> },
    requestedTab: MainTab? = null,
    onTabRequestShown: () -> Unit = {},
    viewModel: HomeViewModel = koinViewModel(),
    waits: WaitsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The waits load while the signed-in app is open, whatever tab shows, and again at each return to it: the badge stays current.
    LifecycleStartEffect(waits) {
        waits.start()
        onStopOrDispose { waits.stop() }
    }
    val waitsState by waits.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onForeground()
        onPauseOrDispose { }
    }
    state.incompatible?.let { incompatible ->
        IncompatibleServerScreen(incompatible, state.account)
        return
    }
    val tabs = state.tabs()
    var selected by rememberSaveable { mutableStateOf<MainTab?>(null) }
    // A tapped notification asked for a tab (a wait's opens the waits).
    LaunchedEffect(requestedTab) {
        requestedTab?.let { selected = it; onTabRequestShown() }
    }
    val tab = selected?.takeIf { it in tabs } ?: tabs.first()
    val wide = LocalWideLayout.current
    val colors = Ostrom.colors
    Column(Modifier.fillMaxSize()) {
        DemoBanner(state.account)
        Row(Modifier.weight(1f).underDemoBanner(state.account)) {
            if (wide && tabs.size > 1) {
                NavigationRail(containerColor = colors.background, modifier = Modifier.testTag("tabs")) {
                    tabs.forEach { item ->
                        NavigationRailItem(
                            selected = item == tab, onClick = { selected = item },
                            icon = { TabIcon(item, waitsState) }, label = { Text(stringResource(item.label)) },
                            colors = NavigationRailItemDefaults.colors(selectedIconColor = colors.accentText, selectedTextColor = colors.accentText, indicatorColor = colors.chip),
                            modifier = Modifier.testTag("tab-${item.name.lowercase()}"),
                        )
                    }
                }
                VerticalDivider(color = colors.line)
            }
            Column(Modifier.weight(1f)) {
                state.account?.takeIf { !it.signedIn }?.let { account ->
                    Banner(stringResource(Res.string.home_signed_out, account.origin), Tone.WARN, Modifier.padding(12.dp).testTag("signed-out")) {
                        AppButton(stringResource(Res.string.home_sign_in_again), onClick = { onSignInAgain(account) }, small = true, fill = false)
                    }
                }
                state.error?.let { Banner(it.words(), Tone.DANGER, Modifier.padding(horizontal = 12.dp, vertical = 6.dp).testTag("home-error")) }
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        MainTab.INBOX -> InboxScreen(onOpen = onOpenRequest, onOpenSettings = { selected = MainTab.SETTINGS })
                        MainTab.WAITING -> WaitsScreen(waits)
                        MainTab.HISTORY -> HistoryScreen()
                        MainTab.SPENDING -> SpendingScreen()
                        MainTab.ACTIVITY -> ActivityScreen(onOpen = if (state.serves(Features.GATES)) onOpenRequest else null)
                        MainTab.ARTIFACTS -> ArtifactsScreen(onOpen = onOpenArtifact)
                        MainTab.SETTINGS -> SettingsScreen(onAddAccount = onAddAccount, onSignInAgain = onSignInAgain, onOpenNotifications = onOpenNotifications)
                    }
                }
            }
        }
        if (!wide && tabs.size > 1) {
            HorizontalDivider(color = colors.line)
            NavigationBar(containerColor = colors.background, modifier = Modifier.testTag("tabs")) {
                tabs.forEach { item ->
                    NavigationBarItem(
                        selected = item == tab, onClick = { selected = item },
                        icon = { TabIcon(item, waitsState) }, label = { Text(stringResource(item.label)) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = colors.accentText, selectedTextColor = colors.accentText, indicatorColor = colors.chip),
                        modifier = Modifier.testTag("tab-${item.name.lowercase()}"),
                    )
                }
            }
        }
    }
}

/** A tab's icon; the Waiting tab's carries the count of open waits, live while another tab shows. */
@Composable
private fun TabIcon(tab: MainTab, waits: WaitsUiState) {
    Box {
        Icon(tab.icon, contentDescription = null)
        if (tab == MainTab.WAITING && waits.openCount > 0) {
            WaitsBadge(waits.openCount, waits.moreOpen, Modifier.align(Alignment.TopEnd).offset(x = 12.dp, y = (-6).dp).testTag("tab-badge-waits"))
        }
    }
}
