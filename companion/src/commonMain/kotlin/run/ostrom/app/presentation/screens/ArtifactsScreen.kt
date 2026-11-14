// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/screens/ArtifactsScreen.kt; see NOTICE.
package run.ostrom.app.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.FileText
import com.adamglin.phosphoricons.regular.Files
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import run.ostrom.app.domain.models.Artifact
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.AppCard
import run.ostrom.app.presentation.components.AppTextField
import run.ostrom.app.presentation.components.Banner
import run.ostrom.app.presentation.components.ButtonKind
import run.ostrom.app.presentation.components.BusyRow
import run.ostrom.app.presentation.components.Chip
import run.ostrom.app.presentation.components.ContentWidth
import run.ostrom.app.presentation.components.EmptyState
import run.ostrom.app.presentation.components.IconTile
import run.ostrom.app.presentation.components.KeepAtTopOnNewFirstRow
import run.ostrom.app.presentation.components.LargeTitle
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.components.dismissKeyboardOnTap
import run.ostrom.app.presentation.components.relativeTime
import run.ostrom.app.presentation.components.rememberNow
import run.ostrom.app.presentation.theme.MetaStyle
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.viewmodels.ArtifactsViewModel
import run.ostrom.app.presentation.viewmodels.words
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.action_load_more
import run.ostrom.app.resources.action_retry
import run.ostrom.app.resources.artifacts_card_details
import run.ostrom.app.resources.artifacts_card_details_undated
import run.ostrom.app.resources.artifacts_empty
import run.ostrom.app.resources.artifacts_empty_body
import run.ostrom.app.resources.artifacts_no_match
import run.ostrom.app.resources.artifacts_search
import run.ostrom.app.resources.artifacts_title

/**
 * The Artifacts tab: the pages the person can read, newest first, searched in the loaded titles and owners. Loaded at
 * each return to the tab; the older pages come as the list nears its end. [onOpen] pushes an artifact's viewer.
 */
@Composable
fun ArtifactsScreen(onOpen: (accountId: String, artifactId: String) -> Unit, viewModel: ArtifactsViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.start()
        onPauseOrDispose { }
    }
    val now = rememberNow()
    val colors = Ostrom.colors
    Column(Modifier.fillMaxSize().background(colors.background).dismissKeyboardOnTap(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LargeTitle(stringResource(Res.string.artifacts_title))
            AppTextField(state.query, viewModel::onQuery, placeholder = stringResource(Res.string.artifacts_search), leading = PhosphorIcons.Regular.MagnifyingGlass, minHeight = 44.dp, tag = "artifacts-search")
            state.error?.let { error ->
                Banner(error.words(), Tone.DANGER, Modifier.testTag("artifacts-error"), action = {
                    AppButton(stringResource(Res.string.action_retry), onClick = viewModel::reload, kind = ButtonKind.SECONDARY, small = true, fill = false, modifier = Modifier.testTag("artifacts-retry"))
                })
            }
        }
        val listState = rememberLazyListState()
        val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 3 } == true } }
        // Searching filters what is loaded; it never pulls every page.
        LaunchedEffect(nearEnd, state.next) { if (nearEnd && state.query.isBlank() && state.error == null) viewModel.loadMore() }
        val shown = state.shown
        KeepAtTopOnNewFirstRow(listState, shown.firstOrNull()?.id ?: "end")
        LazyColumn(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).testTag("artifacts"),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(shown, key = { it.id }) { artifact ->
                ArtifactCard(artifact, now) { state.account?.let { onOpen(it.id, artifact.id) } }
            }
            item(key = "end") {
                BusyRow(state.loading || state.loadingMore)
                if (state.loaded && !state.loading && state.error == null && shown.isEmpty()) {
                    if (state.artifacts.isEmpty()) {
                        EmptyState(PhosphorIcons.Regular.Files, stringResource(Res.string.artifacts_empty), stringResource(Res.string.artifacts_empty_body), Modifier.testTag("artifacts-empty"))
                    } else {
                        Text(stringResource(Res.string.artifacts_no_match), color = colors.muted, modifier = Modifier.fillMaxWidth().padding(24.dp).testTag("artifacts-no-match"))
                    }
                }
                if (state.next != null && !state.loadingMore && !state.loading) {
                    AppButton(stringResource(Res.string.action_load_more), onClick = viewModel::loadMore, kind = ButtonKind.GHOST, modifier = Modifier.testTag("artifacts-more"))
                }
            }
        }
    }
}

/** An artifact: title, access, version, owner and age. */
@Composable
private fun ArtifactCard(artifact: Artifact, now: Long, onClick: () -> Unit) {
    val colors = Ostrom.colors
    AppCard(Modifier.testTag("artifact-${artifact.id}"), onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(PhosphorIcons.Regular.FileText)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(artifact.title, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip(artifact.access)
                    val details = artifact.updatedAt?.let { stringResource(Res.string.artifacts_card_details, artifact.latestVersion, artifact.owner, relativeTime(it, now)) }
                        ?: stringResource(Res.string.artifacts_card_details_undated, artifact.latestVersion, artifact.owner)
                    Text(details, style = MetaStyle, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
