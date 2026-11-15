// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/screens/GitHubLinkScreen.kt; see NOTICE.
package run.ostrom.app.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowSquareOut
import com.adamglin.phosphoricons.regular.ArrowsClockwise
import com.adamglin.phosphoricons.regular.GithubLogo
import run.ostrom.app.domain.models.GitHubLinkState
import run.ostrom.app.presentation.components.Banner
import run.ostrom.app.presentation.components.BusyRow
import run.ostrom.app.presentation.components.ButtonKind
import run.ostrom.app.presentation.components.Chip
import run.ostrom.app.presentation.components.ContentWidth
import run.ostrom.app.presentation.components.Group
import run.ostrom.app.presentation.components.GroupRow
import run.ostrom.app.presentation.components.NavBar
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.AppCard
import run.ostrom.app.presentation.components.AppIconButton
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.theme.MonospaceStyle
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.viewmodels.GitHubDeviceStep
import run.ostrom.app.presentation.viewmodels.GitHubLinkEnd
import run.ostrom.app.presentation.viewmodels.GitHubLinkUiState
import run.ostrom.app.presentation.viewmodels.GitHubLinkViewModel
import run.ostrom.app.presentation.viewmodels.words
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.settings_title
import run.ostrom.app.resources.action_cancel
import run.ostrom.app.resources.github_app_not_configured
import run.ostrom.app.resources.github_code_body
import run.ostrom.app.resources.github_code_title
import run.ostrom.app.resources.github_copied
import run.ostrom.app.resources.github_copy_code
import run.ostrom.app.resources.github_ended_denied
import run.ostrom.app.resources.github_ended_expired
import run.ostrom.app.resources.github_just_linked
import run.ostrom.app.resources.github_link
import run.ostrom.app.resources.github_link_again
import run.ostrom.app.resources.github_linked_as
import run.ostrom.app.resources.github_not_linked
import run.ostrom.app.resources.github_not_offered
import run.ostrom.app.resources.github_open_again
import run.ostrom.app.resources.github_reconnect_required
import run.ostrom.app.resources.github_refresh
import run.ostrom.app.resources.github_title
import run.ostrom.app.resources.github_unknown_state
import run.ostrom.app.resources.github_unlink
import run.ostrom.app.resources.github_unlink_hint
import run.ostrom.app.resources.github_waiting
import org.jetbrains.compose.resources.stringResource

/**
 * The member's GitHub link: which GitHub account is linked, and linking one as the CLI does. The device
 * flow's code is copied and GitHub's page opened in the system browser once; the member pastes the code there.
 */
@Composable
fun GitHubLinkScreen(viewModel: GitHubLinkViewModel, onBack: (() -> Unit)?, backLabel: String = stringResource(Res.string.settings_title)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = Ostrom.colors
    Column(Modifier.fillMaxSize().background(colors.background), horizontalAlignment = Alignment.CenterHorizontally) {
        NavBar(backLabel, onBack, stringResource(Res.string.github_title)) {
            AppIconButton(PhosphorIcons.Regular.ArrowsClockwise, stringResource(Res.string.github_refresh), onClick = viewModel::refresh, enabled = !state.loading && state.step == null,
                modifier = Modifier.testTag("github-refresh"))
        }
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.error?.let { Banner(it.words(), Tone.DANGER, Modifier.testTag("github-error")) }
            state.justLinked?.let { Banner(stringResource(Res.string.github_just_linked, it), Tone.NEUTRAL, Modifier.testTag("github-linked"), icon = null) }
            state.ended?.let {
                Banner(stringResource(if (it == GitHubLinkEnd.EXPIRED) Res.string.github_ended_expired else Res.string.github_ended_denied), Tone.WARN, Modifier.testTag("github-ended"))
            }
            when (val step = state.step) {
                null -> Status(state, viewModel)
                else -> DeviceCode(step, viewModel)
            }
            BusyRow(state.loading || state.starting || state.unlinking)
        }
    }
}

@Composable
private fun Status(state: GitHubLinkUiState, viewModel: GitHubLinkViewModel) {
    val colors = Ostrom.colors
    val link = state.link
    if (!state.offered) {
        if (!state.loading) Banner(stringResource(Res.string.github_not_offered), Tone.WARN, Modifier.testTag("github-not-offered"))
        return
    }
    if (link == null) return
    Group(Modifier.testTag("github-status")) {
        GroupRow {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                when (link.state) {
                    GitHubLinkState.LINKED -> Text(stringResource(Res.string.github_linked_as, link.login.orEmpty()), color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                        modifier = Modifier.testTag("github-login"))
                    GitHubLinkState.RECONNECT_REQUIRED -> Text(stringResource(Res.string.github_reconnect_required, link.login.orEmpty()), color = colors.text, fontSize = 14.sp,
                        modifier = Modifier.testTag("github-reconnect"))
                    GitHubLinkState.UNKNOWN -> Text(stringResource(Res.string.github_unknown_state), color = colors.muted, fontSize = 14.sp,
                        modifier = Modifier.testTag("github-unknown"))
                    GitHubLinkState.NOT_LINKED -> Text(stringResource(if (link.appConfigured) Res.string.github_not_linked else Res.string.github_app_not_configured), color = colors.muted, fontSize = 14.sp,
                        modifier = Modifier.testTag("github-not-linked"))
                }
            }
            if (link.state == GitHubLinkState.LINKED) Chip("GitHub", tone = Tone.ACCENT)
        }
    }
    if (state.canLink) AppButton(stringResource(if (link.state == GitHubLinkState.NOT_LINKED) Res.string.github_link else Res.string.github_link_again), onClick = viewModel::startLinking,
        icon = PhosphorIcons.Regular.GithubLogo, modifier = Modifier.fillMaxWidth().testTag("github-start"))
    if (link.state != GitHubLinkState.NOT_LINKED) {
        AppButton(stringResource(Res.string.github_unlink), onClick = viewModel::unlinkAccount, enabled = state.canUnlink, kind = ButtonKind.DANGER, modifier = Modifier.fillMaxWidth().testTag("github-unlink"))
        Text(stringResource(Res.string.github_unlink_hint), color = colors.muted, fontSize = 12.5.sp)
    }
}

@Composable
@Suppress("DEPRECATION") // LocalClipboardManager: plain text is all the code needs, on every platform.
private fun DeviceCode(step: GitHubDeviceStep, viewModel: GitHubLinkViewModel) {
    val colors = Ostrom.colors
    val browser = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    var copied by remember(step.code) { mutableStateOf(false) }
    // Once per attempt, as the subscription sign-in opens its page: the code is copied, then GitHub's page opens.
    LaunchedEffect(step.code) {
        if (!step.opened) {
            viewModel.browserOpened()
            clipboard.setText(AnnotatedString(step.code))
            copied = true
            runCatching { browser.openUri(step.verificationUri) }
        }
    }
    AppCard(Modifier.testTag("github-device")) {
        Text(stringResource(Res.string.github_code_title), color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(step.code, style = MonospaceStyle, color = colors.text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("github-code"))
        Text(stringResource(Res.string.github_code_body), color = colors.muted, fontSize = 13.5.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(stringResource(if (copied) Res.string.github_copied else Res.string.github_copy_code), onClick = { clipboard.setText(AnnotatedString(step.code)); copied = true },
                kind = ButtonKind.SECONDARY, small = true, modifier = Modifier.testTag("github-copy"))
            AppButton(stringResource(Res.string.github_open_again), onClick = { runCatching { browser.openUri(step.verificationUri) } }, kind = ButtonKind.SECONDARY, small = true,
                icon = PhosphorIcons.Regular.ArrowSquareOut, modifier = Modifier.testTag("github-open"))
        }
        Text(stringResource(Res.string.github_waiting), color = colors.muted, fontSize = 13.sp, modifier = Modifier.testTag("github-waiting"))
        AppButton(stringResource(Res.string.action_cancel), onClick = viewModel::cancelLinking, kind = ButtonKind.SECONDARY, modifier = Modifier.fillMaxWidth().testTag("github-cancel"))
    }
}
