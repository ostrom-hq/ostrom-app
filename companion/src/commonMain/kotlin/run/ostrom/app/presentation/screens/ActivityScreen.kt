// SPDX-License-Identifier: AGPL-3.0-only
// Laid out like TeamCode's companion list screens (Apache-2.0); see NOTICE.
package run.ostrom.app.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Pulse
import com.adamglin.phosphoricons.regular.Warning
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import run.ostrom.app.domain.models.ActivityItem
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.AppCard
import run.ostrom.app.presentation.components.Banner
import run.ostrom.app.presentation.components.ButtonKind
import run.ostrom.app.presentation.components.BusyRow
import run.ostrom.app.presentation.components.ContentWidth
import run.ostrom.app.presentation.components.EmptyState
import run.ostrom.app.presentation.components.KeepAtTopOnNewFirstRow
import run.ostrom.app.presentation.components.LargeTitle
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.components.formatUsd
import run.ostrom.app.presentation.components.rememberNow
import run.ostrom.app.presentation.theme.MetaStyle
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.viewmodels.ActivityViewModel
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.action_load_more
import run.ostrom.app.resources.action_retry
import run.ostrom.app.resources.activity_actor_agent
import run.ostrom.app.resources.activity_empty
import run.ostrom.app.resources.activity_empty_title
import run.ostrom.app.resources.activity_error
import run.ostrom.app.resources.activity_load_failed
import run.ostrom.app.resources.activity_title

/**
 * The organization's activity, newest first: what happened (the server's sentence), who, where, what it cost, when.
 * The first page loads at each return to the tab; older pages as the list nears its end.
 */
@Composable
fun ActivityScreen(viewModel: ActivityViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.start()
        onPauseOrDispose { }
    }
    val colors = Ostrom.colors
    val now = rememberNow()
    val today = localEpochDay(now)
    val language = Locale.current.language
    Column(Modifier.fillMaxSize().background(colors.background), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LargeTitle(stringResource(Res.string.activity_title))
            if (state.error && state.items.isNotEmpty()) Banner(stringResource(Res.string.activity_load_failed), Tone.DANGER, Modifier.testTag("activity-error"), action = {
                AppButton(stringResource(Res.string.action_retry), onClick = viewModel::retry, kind = ButtonKind.SECONDARY, small = true, fill = false, modifier = Modifier.testTag("activity-retry"))
            })
        }
        val listState = rememberLazyListState()
        val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 3 } == true } }
        LaunchedEffect(nearEnd, state.next) { if (nearEnd) viewModel.loadMore() }
        KeepAtTopOnNewFirstRow(listState, state.items.firstOrNull()?.let { "activity-${it.id}" } ?: "end")
        LazyColumn(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).testTag("activity"),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.items, key = { "activity-${it.id}" }) { ActivityRow(it, today, language) }
            item(key = "end") {
                BusyRow(state.loadingMore || (state.refreshing && !state.loaded))
                if (state.loaded && state.items.isEmpty() && !state.error) EmptyState(
                    PhosphorIcons.Regular.Pulse, stringResource(Res.string.activity_empty_title), stringResource(Res.string.activity_empty), Modifier.testTag("activity-empty"),
                )
                if (state.error && state.items.isEmpty()) EmptyState(
                    PhosphorIcons.Regular.Warning, stringResource(Res.string.activity_title), stringResource(Res.string.activity_error), Modifier.testTag("activity-error-state"),
                    dashed = false, iconTint = colors.warn,
                ) {
                    AppButton(stringResource(Res.string.action_retry), onClick = viewModel::retry, modifier = Modifier.testTag("activity-retry"))
                }
                if (state.next != null && !state.loadingMore) {
                    AppButton(stringResource(Res.string.action_load_more), onClick = viewModel::loadMore, kind = ButtonKind.GHOST, modifier = Modifier.testTag("activity-more"))
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(item: ActivityItem, today: Long, language: String) {
    val colors = Ostrom.colors
    val details = listOfNotNull(
        if (item.byAgent) stringResource(Res.string.activity_actor_agent, item.actorName) else item.actorName,
        item.repo?.takeIf { it.isNotBlank() },
        item.costUsd?.let { formatUsd(it, language) },
        item.at?.let { formatEventTime(it, today) },
    )
    AppCard(Modifier.testTag("activity-row-${item.id}")) {
        Text(item.summary, color = colors.text, fontSize = 14.sp)
        Text(details.joinToString(" · "), style = MetaStyle, color = colors.muted)
    }
}
