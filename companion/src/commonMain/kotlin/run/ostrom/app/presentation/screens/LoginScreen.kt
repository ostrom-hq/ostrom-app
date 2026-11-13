// SPDX-License-Identifier: AGPL-3.0-only
// Shaped like TeamCode's companion presentation/screens/LoginScreen.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.GithubLogo
import com.adamglin.phosphoricons.regular.HardDrives
import com.adamglin.phosphoricons.regular.Lock
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.BackLink
import run.ostrom.app.presentation.components.Banner
import run.ostrom.app.presentation.components.ButtonKind
import run.ostrom.app.presentation.components.FormPage
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.navigation.LoginRoute
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.theme.Radii
import run.ostrom.app.presentation.viewmodels.LoginViewModel
import run.ostrom.app.presentation.viewmodels.words
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.action_back
import run.ostrom.app.resources.action_cancel
import run.ostrom.app.resources.action_retry
import run.ostrom.app.resources.login_back_server
import run.ostrom.app.resources.login_browser_hint
import run.ostrom.app.resources.login_copy_code
import run.ostrom.app.resources.login_device_hint
import run.ostrom.app.resources.login_device_intro
import run.ostrom.app.resources.login_invite_only
import run.ostrom.app.resources.login_no_github
import run.ostrom.app.resources.login_open_page
import run.ostrom.app.resources.login_signing_in_to
import run.ostrom.app.resources.login_submit
import run.ostrom.app.resources.login_title
import run.ostrom.app.resources.login_waiting

/**
 * Sign in with GitHub on one server, its origin visible the whole time. A phone opens the system's browser session
 * (the app never sees the GitHub password); a computer shows the code of `ostrom login` and opens the page to enter it.
 */
@Composable
fun LoginScreen(
    route: LoginRoute,
    onBack: () -> Unit,
    onSignedIn: () -> Unit,
    viewModel: LoginViewModel = koinViewModel { parametersOf(route) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.signedIn) { if (state.signedIn != null) onSignedIn() }
    val colors = Ostrom.colors
    val browser = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val server = state.server
    val device = state.deviceLogin
    LaunchedEffect(device) { device?.let { runCatching { browser.openUri(it.verificationUri) } } }
    FormPage(footer = {
        if (server == null && state.error != null) AppButton(stringResource(Res.string.action_retry), onClick = viewModel::loadServer, kind = ButtonKind.SECONDARY, modifier = Modifier.testTag("retry"))
        if (server == null || !server.offersGitHub) return@FormPage
        if (device == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(PhosphorIcons.Regular.Lock, contentDescription = null, tint = colors.muted, modifier = Modifier.size(14.dp))
                Text(stringResource(Res.string.login_browser_hint), color = colors.muted, fontSize = 12.5.sp, lineHeight = 17.sp)
            }
            AppButton(stringResource(Res.string.login_submit), onClick = viewModel::signIn, busy = state.busy, icon = PhosphorIcons.Regular.GithubLogo, modifier = Modifier.testTag("submit"))
        } else {
            AppButton(stringResource(Res.string.login_open_page), onClick = { runCatching { browser.openUri(device.verificationUri) } }, modifier = Modifier.testTag("open-page"))
            AppButton(stringResource(Res.string.action_cancel), onClick = viewModel::cancel, kind = ButtonKind.SECONDARY, modifier = Modifier.testTag("cancel"))
        }
    }) {
        BackLink(stringResource(if (route.accountId != null) Res.string.action_back else Res.string.login_back_server), onBack)
        PageHeading(stringResource(Res.string.login_title), if (viewModel.usesBrowser) null else stringResource(Res.string.login_device_intro))
        ServerCard(PhosphorIcons.Regular.HardDrives, stringResource(Res.string.login_signing_in_to), state.origin, Modifier.testTag("login-server"), mutedTitle = true)
        if (server != null && !server.offersGitHub) Banner(stringResource(Res.string.login_no_github), Tone.DANGER, Modifier.testTag("no-github"))
        if (server?.inviteOnly == true) Banner(stringResource(Res.string.login_invite_only), Tone.NEUTRAL, Modifier.testTag("invite-only"))
        if (device != null) {
            Column(
                Modifier.fillMaxWidth().clip(Radii.card).background(colors.panel).padding(16.dp).testTag("device-code"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(Res.string.login_device_hint), color = colors.muted, fontSize = 13.5.sp)
                SelectionContainer {
                    Text(device.userCode, color = colors.text, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 3.sp,
                        modifier = Modifier.testTag("user-code"))
                }
                AppButton(stringResource(Res.string.login_copy_code), onClick = { clipboard.setText(AnnotatedString(device.userCode)) }, kind = ButtonKind.SECONDARY, small = true, fill = false)
                Text(stringResource(Res.string.login_waiting), color = colors.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        state.error?.let { Banner(it.words(), Tone.DANGER, Modifier.testTag("error")) }
    }
}
