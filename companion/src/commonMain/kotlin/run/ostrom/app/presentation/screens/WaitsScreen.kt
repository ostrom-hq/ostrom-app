// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/screens/WaitsScreen.kt; see NOTICE.
package run.ostrom.app.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CheckCircle
import com.adamglin.phosphoricons.regular.Warning
import run.ostrom.app.domain.services.Clock
import run.ostrom.app.presentation.components.KeepAtTopOnNewFirstRow
import run.ostrom.app.presentation.components.rememberNow
import run.ostrom.app.presentation.components.Banner
import run.ostrom.app.presentation.components.BusyRow
import run.ostrom.app.presentation.components.ButtonKind
import run.ostrom.app.presentation.components.ContentWidth
import run.ostrom.app.presentation.components.EmptyState
import run.ostrom.app.presentation.components.LargeTitle
import run.ostrom.app.presentation.components.Segmented
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.theme.MetaStyle
import run.ostrom.app.presentation.theme.Radii
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.viewmodels.WaitDraft
import run.ostrom.app.presentation.viewmodels.WaitFilter
import run.ostrom.app.presentation.viewmodels.WaitsViewModel
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.action_retry
import run.ostrom.app.resources.action_load_more
import run.ostrom.app.resources.waits_badge_description
import run.ostrom.app.resources.waits_closed_elsewhere
import run.ostrom.app.resources.waits_empty_answered_body
import run.ostrom.app.resources.waits_empty_answered_title
import run.ostrom.app.resources.waits_empty_body
import run.ostrom.app.resources.waits_empty_cancelled_body
import run.ostrom.app.resources.waits_empty_cancelled_title
import run.ostrom.app.resources.waits_empty_title
import run.ostrom.app.resources.waits_error_body
import run.ostrom.app.resources.waits_error_title
import run.ostrom.app.resources.waits_filter_answered
import run.ostrom.app.resources.waits_filter_cancelled
import run.ostrom.app.resources.waits_filter_open
import run.ostrom.app.resources.waits_load_failed
import run.ostrom.app.resources.waits_reconnecting
import run.ostrom.app.resources.waits_title
import run.ostrom.app.resources.waits_in_progress_title
import run.ostrom.app.resources.waits_in_progress_hint
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * "Waiting for you": everything agents asked of the person, newest first, on a tab of its own. Open requests are
 * answered in place; answered and withdrawn ones are read from the filter, with what was given. The list is loaded by
 * the signed-in shell ([WaitsViewModel.start], at each return to the app); the screen itself starts nothing, so its
 * tab's badge counts while the app is open elsewhere. Sessions have no screen in this app yet: a wait shows no link to
 * its session.
 */
@Composable
fun WaitsScreen(
    viewModel: WaitsViewModel,
    clock: Clock = koinInject(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val now = rememberNow(clock)
    val colors = Ostrom.colors
    val shown = state.shown
    Column(Modifier.fillMaxSize().background(colors.background), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LargeTitle(stringResource(Res.string.waits_title)) {
                if (state.openCount > 0) WaitsBadge(state.openCount, state.moreOpen, Modifier.testTag("waits-badge"))
            }
            Segmented(
                listOf(
                    WaitFilter.OPEN to stringResource(Res.string.waits_filter_open), WaitFilter.ANSWERED to stringResource(Res.string.waits_filter_answered),
                    WaitFilter.CANCELLED to stringResource(Res.string.waits_filter_cancelled),
                ),
                state.filter, viewModel::setFilter, Modifier.fillMaxWidth(), tag = { "waits-filter-${it.name.lowercase()}" },
            )
            if (state.reconnecting) Banner(stringResource(Res.string.waits_reconnecting), Tone.WARN, Modifier.testTag("waits-reconnecting"))
            if (state.closedElsewhere) Banner(stringResource(Res.string.waits_closed_elsewhere), Tone.NEUTRAL, Modifier.testTag("waits-closed"), icon = null)
            // A failed load with something to show: the list stays, the banner offers the reload.
            if (state.error && shown.isNotEmpty()) Banner(stringResource(Res.string.waits_load_failed), Tone.DANGER, Modifier.testTag("waits-error"), action = {
                AppButton(stringResource(Res.string.action_retry), onClick = viewModel::retry, kind = ButtonKind.SECONDARY, small = true, modifier = Modifier.testTag("waits-retry"))
            })
        }
        val listState = rememberLazyListState()
        val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 3 } == true } }
        LaunchedEffect(nearEnd, state.shownNext) { if (nearEnd) viewModel.loadMore() }
        KeepAtTopOnNewFirstRow(listState, shown.firstOrNull()?.let { "wait-${it.id}" } ?: "end")
        LazyColumn(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).testTag("waits"),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(shown, key = { "wait-${it.id}" }) { wait ->
                WaitCard(wait, state.drafts[wait.id] ?: WaitDraft(), now, onInput = viewModel::input, onAnswer = viewModel::answer)
            }
            item(key = "end") {
                BusyRow(state.loadingMore || (!state.shownLoaded && !state.error))
                if (state.shownLoaded && shown.isEmpty() && !state.error) WaitsEmpty(state.filter)
                if (state.error && shown.isEmpty()) EmptyState(
                    PhosphorIcons.Regular.Warning, stringResource(Res.string.waits_error_title), stringResource(Res.string.waits_error_body), Modifier.testTag("waits-error-state"),
                    dashed = false, iconTint = colors.warn,
                ) {
                    AppButton(stringResource(Res.string.action_retry), onClick = viewModel::retry, modifier = Modifier.testTag("waits-retry"))
                }
                if (state.shownNext != null && !state.loadingMore) {
                    AppButton(stringResource(Res.string.action_load_more), onClick = viewModel::loadMore, kind = ButtonKind.GHOST, modifier = Modifier.testTag("waits-more"))
                }
            }
            // Below what waits for the member, never mixed with it: what the server watches (a timer, a webhook) and wakes the session for.
            if (state.filter == WaitFilter.OPEN && state.inProgress.isNotEmpty()) {
                item(key = "in-progress-header") {
                    Column(Modifier.padding(top = 10.dp).testTag("waits-in-progress"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(Res.string.waits_in_progress_title), color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("waits-in-progress-title"))
                        Text(stringResource(Res.string.waits_in_progress_hint), style = MetaStyle, color = colors.muted)
                    }
                }
                items(state.inProgress, key = { "in-progress-${it.id}" }) { wait ->
                    InProgressCard(wait, now)
                }
            }
        }
    }
}

@Composable
private fun WaitsEmpty(filter: WaitFilter) {
    val (title, body) = when (filter) {
        WaitFilter.OPEN -> Res.string.waits_empty_title to Res.string.waits_empty_body
        WaitFilter.ANSWERED -> Res.string.waits_empty_answered_title to Res.string.waits_empty_answered_body
        WaitFilter.CANCELLED -> Res.string.waits_empty_cancelled_title to Res.string.waits_empty_cancelled_body
    }
    EmptyState(PhosphorIcons.Regular.CheckCircle, stringResource(title), stringResource(body), Modifier.testTag("waits-empty"))
}

/** The number of open waits in a pill; "12+" when more pages remain, "99+" at most. */
@Composable
fun WaitsBadge(count: Int, more: Boolean, modifier: Modifier = Modifier) {
    val colors = Ostrom.colors
    val text = if (count > 99) "99+" else if (more) "$count+" else "$count"
    val description = stringResource(Res.string.waits_badge_description, text)
    Box(
        modifier.heightIn(min = 18.dp).clip(Radii.pill).background(colors.accent).padding(horizontal = 6.dp).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = colors.onAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
