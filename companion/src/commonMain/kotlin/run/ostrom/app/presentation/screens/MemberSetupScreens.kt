// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion presentation/screens/McpServersScreen.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowSquareOut
import com.adamglin.phosphoricons.regular.ArrowsClockwise
import com.adamglin.phosphoricons.regular.TerminalWindow
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource
import run.ostrom.app.domain.models.McpProcess
import run.ostrom.app.domain.models.McpTransport
import run.ostrom.app.domain.models.MemberEnvironment
import run.ostrom.app.domain.models.PersonalMcpServer
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.AppCard
import run.ostrom.app.presentation.components.AppIconButton
import run.ostrom.app.presentation.components.Banner
import run.ostrom.app.presentation.components.BusyRow
import run.ostrom.app.presentation.components.ButtonKind
import run.ostrom.app.presentation.components.Chip
import run.ostrom.app.presentation.components.ContentWidth
import run.ostrom.app.presentation.components.EmptyState
import run.ostrom.app.presentation.components.Group
import run.ostrom.app.presentation.components.GroupDivider
import run.ostrom.app.presentation.components.GroupRow
import run.ostrom.app.presentation.components.NavBar
import run.ostrom.app.presentation.components.SectionTitle
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.theme.MetaStyle
import run.ostrom.app.presentation.theme.MonospaceStyle
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.viewmodels.MemberEnvironmentViewModel
import run.ostrom.app.presentation.viewmodels.MemberSetupUiState
import run.ostrom.app.presentation.viewmodels.MemberSetupViewModel
import run.ostrom.app.presentation.viewmodels.PersonalMcpServersViewModel
import run.ostrom.app.presentation.viewmodels.words
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.action_retry
import run.ostrom.app.resources.member_env_console_note
import run.ostrom.app.resources.member_env_names_empty
import run.ostrom.app.resources.member_env_names_note
import run.ostrom.app.resources.member_env_names_section
import run.ostrom.app.resources.member_env_no_value
import run.ostrom.app.resources.member_env_refresh
import run.ostrom.app.resources.member_env_title
import run.ostrom.app.resources.member_env_unavailable_note
import run.ostrom.app.resources.member_env_unavailable_section
import run.ostrom.app.resources.member_env_value_stored
import run.ostrom.app.resources.member_env_variants_empty
import run.ostrom.app.resources.member_env_variants_section
import run.ostrom.app.resources.member_mcp_console_note
import run.ostrom.app.resources.member_mcp_empty_body
import run.ostrom.app.resources.member_mcp_empty_title
import run.ostrom.app.resources.member_mcp_off
import run.ostrom.app.resources.member_mcp_refresh
import run.ostrom.app.resources.member_mcp_secrets
import run.ostrom.app.resources.member_mcp_state_backoff
import run.ostrom.app.resources.member_mcp_state_failed
import run.ostrom.app.resources.member_mcp_state_failed_code
import run.ostrom.app.resources.member_mcp_state_idle
import run.ostrom.app.resources.member_mcp_state_ready
import run.ostrom.app.resources.member_mcp_state_starting
import run.ostrom.app.resources.member_mcp_title
import run.ostrom.app.resources.member_mcp_type_http
import run.ostrom.app.resources.member_mcp_type_stdio
import run.ostrom.app.resources.member_setup_open_console
import run.ostrom.app.resources.request_console_not_opened
import run.ostrom.app.resources.request_copied
import run.ostrom.app.resources.request_copy_link
import run.ostrom.app.resources.settings_title

/**
 * The environment variables the member's agents receive, by name only (a value never reaches the app), those withheld
 * because their value cannot be read now, and the member's own personal variants. Read only: a value is written once in
 * the console, which needs a fresh browser sign-in.
 */
@Composable
fun MemberEnvironmentScreen(viewModel: MemberEnvironmentViewModel, onBack: (() -> Unit)?) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = Ostrom.colors
    SetupPage(stringResource(Res.string.member_env_title), stringResource(Res.string.member_env_refresh), "member-env", viewModel, state, onBack) {
        state.content?.let { environment -> EnvironmentContent(environment) }
        Text(stringResource(Res.string.member_env_console_note), color = colors.muted, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.testTag("member-env-note"))
    }
}

@Composable
private fun EnvironmentContent(environment: MemberEnvironment) {
    val colors = Ostrom.colors
    SectionTitle(stringResource(Res.string.member_env_names_section))
    if (environment.names.isEmpty()) {
        Text(stringResource(Res.string.member_env_names_empty), color = colors.muted, fontSize = 14.sp, modifier = Modifier.testTag("member-env-names-empty"))
    } else {
        Group(Modifier.testTag("member-env-names")) {
            environment.names.forEachIndexed { index, name ->
                if (index > 0) GroupDivider()
                GroupRow(Modifier.testTag("member-env-name-$name")) { Text(name, style = MonospaceStyle, color = colors.text) }
            }
        }
    }
    Text(stringResource(Res.string.member_env_names_note), color = colors.muted, fontSize = 12.5.sp, lineHeight = 17.sp)
    if (environment.unavailable.isNotEmpty()) {
        SectionTitle(stringResource(Res.string.member_env_unavailable_section))
        Banner(stringResource(Res.string.member_env_unavailable_note), Tone.WARN, Modifier.testTag("member-env-unavailable-note"))
        Group(Modifier.testTag("member-env-unavailable")) {
            environment.unavailable.forEachIndexed { index, name ->
                if (index > 0) GroupDivider()
                GroupRow(Modifier.testTag("member-env-unavailable-$name")) { Text(name, style = MonospaceStyle, color = colors.warn) }
            }
        }
    }
    SectionTitle(stringResource(Res.string.member_env_variants_section))
    if (environment.variants.isEmpty()) {
        Text(stringResource(Res.string.member_env_variants_empty), color = colors.muted, fontSize = 14.sp, modifier = Modifier.testTag("member-env-variants-empty"))
    } else {
        Group(Modifier.testTag("member-env-variants")) {
            environment.variants.forEachIndexed { index, variant ->
                if (index > 0) GroupDivider()
                GroupRow(Modifier.testTag("member-env-variant-${variant.id}")) {
                    Column(Modifier.weight(1f)) {
                        Text(variant.name, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(variant.variable, style = MetaStyle, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (variant.hasValue) Chip(stringResource(Res.string.member_env_value_stored), tone = Tone.ACCENT, dot = true)
                    else Chip(stringResource(Res.string.member_env_no_value), tone = Tone.WARN)
                }
            }
        }
    }
}

/**
 * The member's personal MCP servers, relayed to them alone: a remote server's address, a hosted command's launcher and
 * pinned package, the names of the secrets each uses, and a hosted process's state. Read only: they are added, edited
 * and deleted in the console.
 */
@Composable
fun PersonalMcpServersScreen(viewModel: PersonalMcpServersViewModel, onBack: (() -> Unit)?) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = Ostrom.colors
    SetupPage(stringResource(Res.string.member_mcp_title), stringResource(Res.string.member_mcp_refresh), "member-mcp", viewModel, state, onBack) {
        val servers = state.content
        if (servers != null && servers.isEmpty()) {
            EmptyState(PhosphorIcons.Regular.TerminalWindow, stringResource(Res.string.member_mcp_empty_title), stringResource(Res.string.member_mcp_empty_body),
                modifier = Modifier.testTag("member-mcp-empty"))
        }
        servers.orEmpty().forEach { server -> McpServerCard(server) }
        Text(stringResource(Res.string.member_mcp_console_note), color = colors.muted, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.testTag("member-mcp-note"))
    }
}

@Composable
private fun McpServerCard(server: PersonalMcpServer) {
    val colors = Ostrom.colors
    AppCard(Modifier.testTag("member-mcp-${server.name}")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(server.name, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (!server.enabled) Chip(stringResource(Res.string.member_mcp_off), tone = Tone.WARN, modifier = Modifier.testTag("member-mcp-off-${server.name}"))
            when (server.transport) {
                McpTransport.HTTP -> Chip(stringResource(Res.string.member_mcp_type_http), tone = Tone.MUTED, modifier = Modifier.testTag("member-mcp-type-${server.name}"))
                McpTransport.STDIO -> Chip(stringResource(Res.string.member_mcp_type_stdio), tone = Tone.MUTED, modifier = Modifier.testTag("member-mcp-type-${server.name}"))
                McpTransport.UNKNOWN -> Unit
            }
        }
        server.agentName?.let { Text(it, style = MetaStyle, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        val source = server.url ?: listOfNotNull(server.launcher, server.packageName).joinToString(" ").ifEmpty { null }
        source?.let { Text(it, style = MonospaceStyle, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("member-mcp-source-${server.name}")) }
        if (server.secretNames.isNotEmpty()) {
            Text(stringResource(Res.string.member_mcp_secrets, server.secretNames.joinToString(", ")), style = MetaStyle, color = colors.muted,
                modifier = Modifier.testTag("member-mcp-secrets-${server.name}"))
        }
        McpProcessLine(server)
    }
}

@Composable
private fun McpProcessLine(server: PersonalMcpServer) {
    val tag = Modifier.testTag("member-mcp-state-${server.name}")
    when (val process = server.process) {
        McpProcess.Untracked -> Unit
        McpProcess.Ready -> Chip(stringResource(Res.string.member_mcp_state_ready), tone = Tone.ACCENT, dot = true, modifier = tag)
        McpProcess.Starting -> Chip(stringResource(Res.string.member_mcp_state_starting), tone = Tone.WARN, dot = true, modifier = tag)
        McpProcess.Idle -> Chip(stringResource(Res.string.member_mcp_state_idle), tone = Tone.MUTED, dot = true, modifier = tag)
        McpProcess.Backoff -> Chip(stringResource(Res.string.member_mcp_state_backoff), tone = Tone.WARN, dot = true, modifier = tag)
        is McpProcess.Failed -> Text(
            process.code?.let { stringResource(Res.string.member_mcp_state_failed_code, it) } ?: stringResource(Res.string.member_mcp_state_failed),
            color = Ostrom.colors.danger, fontSize = 13.sp, lineHeight = 18.sp, modifier = tag,
        )
    }
}

/** A read-only page of the member's setup: its bar with Refresh, the last failure, the content, and the console's link. */
@Composable
private fun <T> SetupPage(
    title: String,
    refreshLabel: String,
    tag: String,
    viewModel: MemberSetupViewModel<T>,
    state: MemberSetupUiState<T>,
    onBack: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Ostrom.colors
    Column(Modifier.fillMaxSize().background(colors.background), horizontalAlignment = Alignment.CenterHorizontally) {
        NavBar(stringResource(Res.string.settings_title), onBack, title) {
            AppIconButton(PhosphorIcons.Regular.ArrowsClockwise, refreshLabel, onClick = viewModel::refresh, enabled = !state.loading, modifier = Modifier.testTag("$tag-refresh"))
        }
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp).testTag(tag),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { error ->
                Banner(error.words(), Tone.DANGER, Modifier.testTag("$tag-error"), action = {
                    AppButton(stringResource(Res.string.action_retry), onClick = viewModel::refresh, kind = ButtonKind.SECONDARY, small = true, fill = false,
                        modifier = Modifier.testTag("$tag-retry"))
                })
            }
            BusyRow(state.loading && state.content == null)
            content()
            state.consoleUrl?.let { url -> ConsoleAction(url, tag) }
        }
    }
}

/** Opens [url] in the browser; when no browser opens it, shows the address to copy. */
@Suppress("DEPRECATION") // LocalClipboardManager: plain text is all the link needs, on every platform.
@Composable
private fun ConsoleAction(url: String, tag: String) {
    val colors = Ostrom.colors
    val browser = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    var failed by remember(url) { mutableStateOf(false) }
    var copied by remember(url) { mutableStateOf(false) }
    AppButton(stringResource(Res.string.member_setup_open_console), icon = PhosphorIcons.Regular.ArrowSquareOut, kind = ButtonKind.SECONDARY,
        modifier = Modifier.testTag("$tag-open-console"), onClick = {
            failed = try {
                browser.openUri(url)
                false
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // No browser, or a link the system refused: the address is shown to copy.
                true
            }
        })
    if (failed) {
        Text(stringResource(Res.string.request_console_not_opened), color = colors.text, fontSize = 13.5.sp)
        SelectionContainer { Text(url, style = MonospaceStyle, color = colors.text, modifier = Modifier.testTag("$tag-console-url")) }
        AppButton(stringResource(if (copied) Res.string.request_copied else Res.string.request_copy_link), onClick = {
            clipboard.setText(AnnotatedString(url))
            copied = true
        }, kind = ButtonKind.SECONDARY, small = true, fill = false, modifier = Modifier.testTag("$tag-copy-console"))
    }
}
