// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/screens/SessionsScreen.kt; see NOTICE.
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
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowsClockwise
import com.adamglin.phosphoricons.regular.TerminalWindow
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import run.ostrom.app.domain.models.AgentSession
import run.ostrom.app.domain.models.SessionFilter
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.AppCard
import run.ostrom.app.presentation.components.AppIconButton
import run.ostrom.app.presentation.components.Banner
import run.ostrom.app.presentation.components.ButtonKind
import run.ostrom.app.presentation.components.BusyRow
import run.ostrom.app.presentation.components.Chip
import run.ostrom.app.presentation.components.ContentWidth
import run.ostrom.app.presentation.components.EmptyState
import run.ostrom.app.presentation.components.KeepAtTopOnNewFirstRow
import run.ostrom.app.presentation.components.LargeTitle
import run.ostrom.app.presentation.components.Segmented
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.components.formatUsd
import run.ostrom.app.presentation.components.relativeTime
import run.ostrom.app.presentation.components.rememberNow
import run.ostrom.app.presentation.theme.MetaStyle
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.viewmodels.SessionsViewModel
import run.ostrom.app.presentation.viewmodels.words
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.action_load_more
import run.ostrom.app.resources.action_retry
import run.ostrom.app.resources.session_state_ended
import run.ostrom.app.resources.session_state_lost
import run.ostrom.app.resources.session_state_open
import run.ostrom.app.resources.session_state_waiting
import run.ostrom.app.resources.sessions_empty
import run.ostrom.app.resources.sessions_empty_body
import run.ostrom.app.resources.sessions_ended_ago
import run.ostrom.app.resources.sessions_filter_all
import run.ostrom.app.resources.sessions_filter_ended
import run.ostrom.app.resources.sessions_filter_open
import run.ostrom.app.resources.sessions_no_repo
import run.ostrom.app.resources.sessions_reconnecting
import run.ostrom.app.resources.sessions_refresh
import run.ostrom.app.resources.sessions_started_ago
import run.ostrom.app.resources.sessions_title
import run.ostrom.app.resources.sessions_tools
import run.ostrom.app.resources.sessions_unavailable

/**
 * The Sessions tab: the organization's agent sessions, open and ended, newest first, keyset-paged, with the repository,
 * agent, model, person, start or end, cost and tools. Kept current while shown by the app's one feed (a load when it
 * opens and after a gap), and at each return and refresh. [onOpen] pushes a session's record.
 */
@Composable
fun SessionsScreen(onOpen: (accountId: String, sessionId: String) -> Unit, viewModel: SessionsViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleStartEffect(viewModel) {
        viewModel.start()
        onStopOrDispose { viewModel.stop() }
    }
    val now = rememberNow()
    val language = Locale.current.language
    val colors = Ostrom.colors
    Column(Modifier.fillMaxSize().background(colors.background), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LargeTitle(stringResource(Res.string.sessions_title)) {
                AppIconButton(PhosphorIcons.Regular.ArrowsClockwise, stringResource(Res.string.sessions_refresh), onClick = viewModel::reload, modifier = Modifier.testTag("sessions-refresh"))
            }
            Segmented(
                listOf(
                    SessionFilter.ALL to stringResource(Res.string.sessions_filter_all), SessionFilter.OPEN to stringResource(Res.string.sessions_filter_open),
                    SessionFilter.ENDED to stringResource(Res.string.sessions_filter_ended),
                ),
                state.filter, viewModel::setFilter, Modifier.fillMaxWidth(), tag = { "sessions-filter-${it.name.lowercase()}" },
            )
            if (state.reconnecting) Banner(stringResource(Res.string.sessions_reconnecting), Tone.WARN, Modifier.testTag("sessions-reconnecting"))
            if (state.unavailable) Banner(stringResource(Res.string.sessions_unavailable), Tone.WARN, Modifier.testTag("sessions-unavailable"))
            state.error?.let { error ->
                Banner(error.words(), Tone.DANGER, Modifier.testTag("sessions-error"), action = {
                    AppButton(stringResource(Res.string.action_retry), onClick = viewModel::reload, kind = ButtonKind.SECONDARY, small = true, fill = false, modifier = Modifier.testTag("sessions-retry"))
                })
            }
        }
        val listState = rememberLazyListState()
        val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 3 } == true } }
        LaunchedEffect(nearEnd, state.next) { if (nearEnd && state.error == null) viewModel.loadMore() }
        KeepAtTopOnNewFirstRow(listState, state.sessions.firstOrNull()?.id ?: "end")
        LazyColumn(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).testTag("sessions"),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.sessions, key = { it.id }) { session ->
                SessionCard(session, now, language) { state.account?.let { onOpen(it.id, session.id) } }
            }
            item(key = "end") {
                BusyRow(state.loading || state.loadingMore)
                if (state.empty && state.error == null && !state.unavailable) {
                    EmptyState(PhosphorIcons.Regular.TerminalWindow, stringResource(Res.string.sessions_empty), stringResource(Res.string.sessions_empty_body), Modifier.testTag("sessions-empty"))
                }
                if (state.next != null && !state.loadingMore && !state.loading) {
                    AppButton(stringResource(Res.string.action_load_more), onClick = viewModel::loadMore, kind = ButtonKind.GHOST, modifier = Modifier.testTag("sessions-more"))
                }
            }
        }
    }
}

/** A session: its repository and state, the agent and model, who ran it and when, what it cost and how many tools it ran. */
@Composable
private fun SessionCard(session: AgentSession, now: Long, language: String, onClick: () -> Unit) {
    val colors = Ostrom.colors
    AppCard(Modifier.testTag("session-${session.id}"), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(session.repo ?: stringResource(Res.string.sessions_no_repo), color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            SessionStateChip(session, Modifier.testTag("session-state-${session.id}"))
        }
        Text(listOfNotNull(session.agent, session.model).joinToString(" · "), color = colors.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val ended = session.endedAt
        val started = session.startedAt
        val time = when {
            ended != null -> stringResource(Res.string.sessions_ended_ago, relativeTime(ended, now))
            started != null -> stringResource(Res.string.sessions_started_ago, relativeTime(started, now))
            else -> null
        }
        Text(listOfNotNull(session.dev, time).joinToString(" · "), style = MetaStyle, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("session-time-${session.id}"))
        val spend = listOfNotNull(session.costUsd?.let { formatUsd(it, language) }, session.tools?.let { stringResource(Res.string.sessions_tools, it) })
        if (spend.isNotEmpty()) Text(spend.joinToString(" · "), style = MetaStyle, color = colors.muted, modifier = Modifier.testTag("session-spend-${session.id}"))
    }
}

/** Open (and what it does now), ended, or lost: the launcher stopped answering before it ended. */
@Composable
internal fun SessionStateChip(session: AgentSession, modifier: Modifier = Modifier) {
    when {
        session.lost -> Chip(stringResource(Res.string.session_state_lost), modifier, tone = Tone.WARN)
        session.endedAt != null -> Chip(stringResource(Res.string.session_state_ended), modifier, tone = Tone.MUTED)
        session.activity == "waiting_for_human" -> Chip(stringResource(Res.string.session_state_waiting), modifier, tone = Tone.ACCENT, dot = true)
        else -> Chip(stringResource(Res.string.session_state_open), modifier, tone = Tone.ACCENT, dot = true)
    }
}
