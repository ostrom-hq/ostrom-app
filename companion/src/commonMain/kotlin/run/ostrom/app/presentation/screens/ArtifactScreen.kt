// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/screens/ArtifactScreen.kt; see NOTICE.
package run.ostrom.app.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowSquareOut
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.Copy
import com.adamglin.phosphoricons.regular.FileText
import org.jetbrains.compose.resources.stringResource
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.AppIconButton
import run.ostrom.app.presentation.components.AppSheet
import run.ostrom.app.presentation.components.ArtifactContentView
import run.ostrom.app.presentation.components.Banner
import run.ostrom.app.presentation.components.ButtonKind
import run.ostrom.app.presentation.components.BusyRow
import run.ostrom.app.presentation.components.Chip
import run.ostrom.app.presentation.components.ConfirmingLinks
import run.ostrom.app.presentation.components.ContentWidth
import run.ostrom.app.presentation.components.EmptyState
import run.ostrom.app.presentation.components.Group
import run.ostrom.app.presentation.components.GroupDivider
import run.ostrom.app.presentation.components.NavBar
import run.ostrom.app.presentation.components.SheetHeader
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.components.embedsArtifacts
import run.ostrom.app.presentation.components.relativeTime
import run.ostrom.app.presentation.components.rememberNow
import run.ostrom.app.presentation.theme.MetaStyle
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.theme.Radii
import run.ostrom.app.presentation.viewmodels.ArtifactViewModel
import run.ostrom.app.presentation.viewmodels.words
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.action_done
import run.ostrom.app.resources.action_retry
import run.ostrom.app.resources.artifact_choose_version
import run.ostrom.app.resources.artifact_content
import run.ostrom.app.resources.artifact_copied
import run.ostrom.app.resources.artifact_copy_link
import run.ostrom.app.resources.artifact_desktop
import run.ostrom.app.resources.artifact_desktop_no_link
import run.ostrom.app.resources.artifact_missing_page
import run.ostrom.app.resources.artifact_newer
import run.ostrom.app.resources.artifact_open_browser
import run.ostrom.app.resources.artifact_restart
import run.ostrom.app.resources.artifact_show_newer
import run.ostrom.app.resources.artifact_versions
import run.ostrom.app.resources.artifact_versions_title
import run.ostrom.app.resources.artifacts_title

/**
 * One artifact under a native bar: title, copy link and open in the browser (its console page), then the version
 * shown, its access and owner. On iOS and Android the content shows in a sandboxed web view, from a short-lived
 * ticket on the server's content origin; the desktop opens the console page in the browser. A link inside the
 * content opens outside only after its URL is shown.
 */
@Suppress("DEPRECATION") // LocalClipboardManager: plain text is all the link needs, on every platform.
@Composable
fun ArtifactScreen(viewModel: ArtifactViewModel, onBack: (() -> Unit)?, backLabel: String = stringResource(Res.string.artifacts_title)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleStartEffect(viewModel) {
        viewModel.start()
        onStopOrDispose { viewModel.stop() }
    }
    val clipboard = LocalClipboardManager.current
    val browser = LocalUriHandler.current
    var copied by remember { mutableStateOf(false) }
    var versionsOpen by remember { mutableStateOf(false) }
    // The confirmation shows for a moment, then the access and the owner come back.
    LaunchedEffect(copied) { if (copied) { delay(COPIED_MILLIS); copied = false } }
    val now = rememberNow()
    val colors = Ostrom.colors
    Column(Modifier.fillMaxSize().background(colors.background)) {
        NavBar(backLabel, onBack, state.artifact?.title.orEmpty()) {
            val url = state.viewerUrl
            if (state.artifact != null && url != null) {
                AppIconButton(if (copied) PhosphorIcons.Regular.Check else PhosphorIcons.Regular.Copy, stringResource(Res.string.artifact_copy_link),
                    onClick = { clipboard.setText(AnnotatedString(url)); copied = true }, modifier = Modifier.testTag("artifact-copy"), tint = if (copied) colors.accentText else null)
                AppIconButton(PhosphorIcons.Regular.ArrowSquareOut, stringResource(Res.string.artifact_open_browser), onClick = { browser.openUri(url) }, modifier = Modifier.testTag("artifact-browser"))
            }
        }
        state.artifact?.let { artifact ->
            Column(Modifier.fillMaxWidth().background(colors.line).padding(bottom = 1.dp)) {
                Row(
                    Modifier.fillMaxWidth().background(colors.background).padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.version?.let { version ->
                        val age = state.versions.firstOrNull { it.number == version }?.createdAt?.let { relativeTime(it, now) }
                        VersionButton(version, age, enabled = state.versions.size > 1) { versionsOpen = true }
                    }
                    Spacer(Modifier.weight(1f))
                    if (copied) {
                        Text(stringResource(Res.string.artifact_copied), color = colors.muted, fontSize = 12.sp)
                    } else {
                        Chip(artifact.access)
                        Text(artifact.owner, style = MetaStyle, color = colors.muted, maxLines = 1)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.newerVersion != null || state.missingPage || state.error != null || state.loading) Spacer(Modifier.height(2.dp))
            state.newerVersion?.let { newer ->
                Banner(stringResource(Res.string.artifact_newer, newer), Tone.ACCENT, icon = null, action = {
                    AppButton(stringResource(Res.string.artifact_show_newer), onClick = viewModel::showNewer, small = true, fill = false, modifier = Modifier.testTag("artifact-newer"))
                })
            }
            if (state.missingPage) Banner(stringResource(Res.string.artifact_missing_page), Tone.WARN, action = {
                AppButton(stringResource(Res.string.artifact_restart), onClick = viewModel::restart, kind = ButtonKind.SECONDARY, small = true, fill = false, modifier = Modifier.testTag("artifact-restart"))
            })
            state.error?.let { error ->
                Banner(error.words(), Tone.DANGER, Modifier.testTag("artifact-error"), action = if (!state.gone) {
                    { AppButton(stringResource(Res.string.action_retry), onClick = viewModel::load, kind = ButtonKind.SECONDARY, small = true, fill = false, modifier = Modifier.testTag("artifact-retry")) }
                } else null)
            }
            BusyRow(state.loading)
        }
        val opened = state.opened
        val description = stringResource(Res.string.artifact_content)
        when {
            !embedsArtifacts -> state.artifact?.let {
                Box(Modifier.fillMaxWidth().weight(1f).navigationBarsPadding(), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = ContentWidth).fillMaxWidth().padding(16.dp)) {
                        val url = state.viewerUrl
                        EmptyState(PhosphorIcons.Regular.FileText, it.title, stringResource(if (url != null) Res.string.artifact_desktop else Res.string.artifact_desktop_no_link),
                            Modifier.testTag("artifact-desktop"), dashed = false) {
                            if (url != null) AppButton(stringResource(Res.string.artifact_open_browser), onClick = { browser.openUri(url) }, icon = PhosphorIcons.Regular.ArrowSquareOut,
                                fill = false, modifier = Modifier.testTag("artifact-open-desktop"))
                        }
                    }
                }
            }
            opened != null -> ConfirmingLinks {
                val confirm = LocalUriHandler.current
                Box(Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp).background(colors.line).padding(top = 1.dp)) {
                    ArtifactContentView(state.page ?: opened.ticket.contentUrl, opened.prefix, opened.html, state.reload, onExternal = confirm::openUri,
                        onMissing = viewModel::contentMissing, onCrashed = viewModel::contentCrashed,
                        modifier = Modifier.fillMaxSize().semantics { contentDescription = description }.testTag("artifact-content"))
                }
            }
        }
    }
    if (versionsOpen) {
        val version = state.version
        AppSheet(onDismiss = { versionsOpen = false }) {
            SheetHeader(stringResource(Res.string.artifact_versions_title), trailing = {
                AppButton(stringResource(Res.string.action_done), onClick = { versionsOpen = false }, kind = ButtonKind.GHOST, small = true, fill = false)
            })
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                Group(Modifier.selectableGroup()) {
                    state.versions.forEachIndexed { index, item ->
                        if (index > 0) GroupDivider()
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .selectable(selected = item.number == version, role = Role.RadioButton) { versionsOpen = false; viewModel.selectVersion(item.number) }
                                .padding(horizontal = 14.dp).testTag("artifact-version-${item.number}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(stringResource(Res.string.artifact_versions, item.number), color = colors.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(listOfNotNull(item.createdBy.takeIf { it.isNotBlank() }, item.createdAt?.let { relativeTime(it, now) }).joinToString(" · "), style = MetaStyle, color = colors.muted)
                            Box(Modifier.size(20.dp)) {
                                if (item.number == version) Icon(PhosphorIcons.Regular.Check, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val COPIED_MILLIS = 2_000L

/** "Version 3 · 2 h ⌄": opens the versions sheet when there is more than one. */
@Composable
private fun VersionButton(version: Int, age: String?, enabled: Boolean, onClick: () -> Unit) {
    val colors = Ostrom.colors
    val description = stringResource(Res.string.artifact_choose_version)
    Row(
        Modifier.heightIn(min = 44.dp).clip(Radii.control).background(colors.panel).border(1.dp, colors.line, Radii.control)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = description }.padding(horizontal = 12.dp).testTag("artifact-versions"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(Res.string.artifact_versions, version), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (age != null) Text(age, style = MetaStyle, color = colors.muted)
        Icon(PhosphorIcons.Regular.CaretDown, contentDescription = null, tint = colors.muted, modifier = Modifier.size(14.dp).alpha(if (enabled) 1f else 0.4f))
    }
}
