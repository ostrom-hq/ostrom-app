// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion presentation/screens/AccountScreen.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.Plus
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import run.ostrom.app.demo.isDemo
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.ThemePreference
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.AppSheet
import run.ostrom.app.presentation.components.Avatar
import run.ostrom.app.presentation.components.Banner
import run.ostrom.app.presentation.components.ButtonKind
import run.ostrom.app.presentation.components.Centered
import run.ostrom.app.presentation.components.Chip
import run.ostrom.app.presentation.components.Group
import run.ostrom.app.presentation.components.GroupDivider
import run.ostrom.app.presentation.components.GroupRow
import run.ostrom.app.presentation.components.LargeTitle
import run.ostrom.app.presentation.components.SectionTitle
import run.ostrom.app.presentation.components.Segmented
import run.ostrom.app.presentation.components.SheetHeader
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.theme.MonospaceStyle
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.viewmodels.SettingsViewModel
import run.ostrom.app.presentation.viewmodels.words
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.action_close
import run.ostrom.app.resources.notifications_title
import run.ostrom.app.resources.settings_about
import run.ostrom.app.resources.settings_account_active
import run.ostrom.app.resources.settings_account_signed_out
import run.ostrom.app.resources.settings_accounts
import run.ostrom.app.resources.settings_add_account
import run.ostrom.app.resources.settings_api_version
import run.ostrom.app.resources.settings_api_version_value
import run.ostrom.app.resources.settings_app_version
import run.ostrom.app.resources.settings_appearance
import run.ostrom.app.resources.settings_change_organization
import run.ostrom.app.resources.settings_end_demo
import run.ostrom.app.resources.settings_memory_only
import run.ostrom.app.resources.settings_no_organization
import run.ostrom.app.resources.settings_notifications_row
import run.ostrom.app.resources.settings_organization
import run.ostrom.app.resources.settings_server_version
import run.ostrom.app.resources.settings_sign_in_again
import run.ostrom.app.resources.settings_sign_out
import run.ostrom.app.resources.settings_title
import run.ostrom.app.resources.settings_unknown
import run.ostrom.app.resources.theme_dark
import run.ostrom.app.resources.theme_light
import run.ostrom.app.resources.theme_system

/**
 * The accounts on this device (switch, add, sign out or in again), the organization the active one acts in, the
 * theme, and the versions of the app, of the server and of the API they speak.
 */
@Composable
fun SettingsScreen(
    onAddAccount: () -> Unit,
    onSignInAgain: (Account) -> Unit,
    onOpenNotifications: (accountId: String) -> Unit = {},
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = Ostrom.colors
    var choosingOrganization by remember { mutableStateOf(false) }
    val active = state.activeAccount
    Centered(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp, vertical = 12.dp).testTag("settings"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LargeTitle(stringResource(Res.string.settings_title))
            state.message?.let { Banner(it.words(), Tone.NEUTRAL, Modifier.testTag("settings-message")) }
            if (!state.persistentCredentials) Banner(stringResource(Res.string.settings_memory_only), Tone.WARN, Modifier.testTag("memory-only"))

            SectionTitle(stringResource(Res.string.settings_accounts))
            Group {
                state.accounts.forEachIndexed { index, account ->
                    if (index > 0) GroupDivider()
                    GroupRow(Modifier.testTag("account-${account.id}"), onClick = { if (account.signedIn) viewModel.switchTo(account) else onSignInAgain(account) }) {
                        Avatar(account.displayName, dimmed = !account.signedIn)
                        Column(Modifier.weight(1f)) {
                            Text(account.displayName, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(account.origin, style = MonospaceStyle, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        when {
                            !account.signedIn -> Chip(stringResource(Res.string.settings_account_signed_out), tone = Tone.WARN)
                            account.id == state.activeAccountId -> Chip(stringResource(Res.string.settings_account_active), tone = Tone.ACCENT, dot = true)
                        }
                    }
                }
                if (active?.isDemo != true) {
                    if (state.accounts.isNotEmpty()) GroupDivider()
                    GroupRow(Modifier.testTag("add-account"), onClick = onAddAccount) {
                        Icon(PhosphorIcons.Regular.Plus, contentDescription = null, tint = colors.accentText)
                        Text(stringResource(Res.string.settings_add_account), color = colors.accentText, fontSize = 15.sp)
                    }
                }
            }

            if (active != null && active.signedIn && !active.isDemo) {
                SectionTitle(stringResource(Res.string.settings_organization))
                Group {
                    GroupRow(Modifier.testTag("organization"), onClick = { choosingOrganization = true; viewModel.loadOrganizations() }) {
                        Text(active.organizationName ?: stringResource(Res.string.settings_no_organization), color = colors.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text(stringResource(Res.string.settings_change_organization), color = colors.accentText, fontSize = 14.sp)
                    }
                }
            }

            if (active != null && active.signedIn && state.server?.features?.contains(Features.NOTIFICATIONS) == true) {
                SectionTitle(stringResource(Res.string.notifications_title))
                Group {
                    GroupRow(Modifier.testTag("settings-notifications"), onClick = { onOpenNotifications(active.id) }) {
                        Text(stringResource(Res.string.settings_notifications_row), color = colors.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Icon(PhosphorIcons.Regular.CaretRight, contentDescription = null, tint = colors.muted)
                    }
                }
            }

            SectionTitle(stringResource(Res.string.settings_appearance))
            Segmented(
                options = listOf(
                    ThemePreference.SYSTEM to stringResource(Res.string.theme_system),
                    ThemePreference.LIGHT to stringResource(Res.string.theme_light),
                    ThemePreference.DARK to stringResource(Res.string.theme_dark),
                ),
                selected = state.theme,
                onSelect = viewModel::setTheme,
                tag = { "theme-${it.name.lowercase()}" },
            )

            SectionTitle(stringResource(Res.string.settings_about))
            Group {
                VersionRow(stringResource(Res.string.settings_app_version), state.appVersion, "app-version")
                GroupDivider()
                VersionRow(stringResource(Res.string.settings_server_version), state.server?.serverVersion ?: stringResource(Res.string.settings_unknown), "server-version")
                GroupDivider()
                VersionRow(
                    stringResource(Res.string.settings_api_version),
                    stringResource(Res.string.settings_api_version_value, state.server?.apiVersion?.toString() ?: stringResource(Res.string.settings_unknown), state.supportedApiVersion),
                    "api-version",
                )
            }

            if (active != null) {
                if (!active.signedIn) AppButton(stringResource(Res.string.settings_sign_in_again), onClick = { onSignInAgain(active) }, modifier = Modifier.testTag("sign-in-again"))
                AppButton(
                    stringResource(if (active.isDemo) Res.string.settings_end_demo else Res.string.settings_sign_out),
                    onClick = { viewModel.signOut(active) }, kind = ButtonKind.DANGER, busy = state.working, modifier = Modifier.testTag("sign-out"),
                )
            }
        }
    }
    if (choosingOrganization) {
        AppSheet(onDismiss = { choosingOrganization = false }) {
            SheetHeader(stringResource(Res.string.settings_organization), trailing = {
                AppButton(stringResource(Res.string.action_close), onClick = { choosingOrganization = false }, kind = ButtonKind.GHOST, small = true, fill = false)
            })
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.message?.let { Banner(it.words(), Tone.DANGER) }
                Group {
                    state.organizations.orEmpty().forEachIndexed { index, organization ->
                        if (index > 0) GroupDivider()
                        GroupRow(Modifier.testTag("organization-${organization.id}"), onClick = { viewModel.chooseOrganization(organization); choosingOrganization = false }) {
                            Text(organization.name, color = colors.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            if (organization.id == active?.organizationId) Icon(PhosphorIcons.Regular.Check, contentDescription = null, tint = colors.accentText)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionRow(label: String, value: String, tag: String) {
    val colors = Ostrom.colors
    GroupRow {
        Text(label, color = colors.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, style = MonospaceStyle, color = colors.muted, modifier = Modifier.testTag(tag))
    }
}
