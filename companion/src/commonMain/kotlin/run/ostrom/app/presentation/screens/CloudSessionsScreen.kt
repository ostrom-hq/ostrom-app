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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowsClockwise
import com.adamglin.phosphoricons.regular.Cloud
import com.adamglin.phosphoricons.regular.Plus
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import run.ostrom.app.domain.models.CloudPhase
import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.domain.models.CloudSessionStatus
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.AppCard
import run.ostrom.app.presentation.components.AppIconButton
import run.ostrom.app.presentation.components.Banner
import run.ostrom.app.presentation.components.ButtonKind
import run.ostrom.app.presentation.components.BusyRow
import run.ostrom.app.presentation.components.Chip
import run.ostrom.app.presentation.components.KindChips
import run.ostrom.app.presentation.components.ContentWidth
import run.ostrom.app.presentation.components.EmptyState
import run.ostrom.app.presentation.components.IconButtonKind
import run.ostrom.app.presentation.components.LargeTitle
import run.ostrom.app.presentation.components.Segmented
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.components.TransfersButton
import run.ostrom.app.presentation.components.relativeTime
import run.ostrom.app.presentation.components.rememberNow
import run.ostrom.app.presentation.theme.MetaStyle
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.viewmodels.CloudSessionsViewModel
import run.ostrom.app.presentation.viewmodels.words
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.action_load_more
import run.ostrom.app.resources.action_retry
import run.ostrom.app.resources.cloud_phase_cloning_repositories
import run.ostrom.app.resources.cloud_phase_configuring_git
import run.ostrom.app.resources.cloud_phase_fetching_repositories
import run.ostrom.app.resources.cloud_phase_other
import run.ostrom.app.resources.cloud_phase_running_setup
import run.ostrom.app.resources.cloud_sessions_created_ago
import run.ostrom.app.resources.cloud_sessions_empty
import run.ostrom.app.resources.cloud_sessions_empty_body
import run.ostrom.app.resources.cloud_sessions_new
import run.ostrom.app.resources.cloud_sessions_refresh
import run.ostrom.app.resources.cloud_sessions_title
import run.ostrom.app.resources.cloud_sessions_unavailable
import run.ostrom.app.resources.cloud_status_creating
import run.ostrom.app.resources.cloud_status_failed
import run.ostrom.app.resources.cloud_status_paused
import run.ostrom.app.resources.cloud_status_pausing
import run.ostrom.app.resources.cloud_status_resuming
import run.ostrom.app.resources.cloud_status_running
import run.ostrom.app.resources.cloud_status_terminated
import run.ostrom.app.resources.cloud_status_transferring
import run.ostrom.app.resources.cloud_status_unknown
import run.ostrom.app.resources.sessions_kind_agent
import run.ostrom.app.resources.sessions_kind_cloud
import run.ostrom.app.resources.sessions_title
import run.ostrom.app.resources.session_phase_waiting_host

/** Which sessions the Sessions tab shows: the organization's agent sessions, or the person's cloud sessions. */
enum class SessionsKind { AGENT, CLOUD }

/**
 * The Sessions tab: the agent sessions (`sessions.v1`) and the cloud sessions (`runners.cloud.v1`), as TeamCode shows
 * its cloud sessions in its Sessions tab. With both served, a switch under the title picks the list; with one, it shows.
 */
@Composable
fun SessionsTab(
    agentServed: Boolean,
    cloudServed: Boolean,
    onOpenSession: (accountId: String, sessionId: String) -> Unit,
    onOpenCloudSession: (accountId: String, sessionId: String) -> Unit,
    onNewCloudSession: (accountId: String) -> Unit,
    onOpenTransfers: (accountId: String) -> Unit = {},
) {
    var chosen by rememberSaveable { mutableStateOf(SessionsKind.AGENT) }
    val kind = when {
        !cloudServed -> SessionsKind.AGENT
        !agentServed -> SessionsKind.CLOUD
        else -> chosen
    }
    val switch: (@Composable () -> Unit)? = if (agentServed && cloudServed) {
        {
            Segmented(
                listOf(SessionsKind.AGENT to stringResource(Res.string.sessions_kind_agent), SessionsKind.CLOUD to stringResource(Res.string.sessions_kind_cloud)),
                kind, { chosen = it }, Modifier.fillMaxWidth(), tag = { "sessions-kind-${it.name.lowercase()}" },
            )
        }
    } else null
    when (kind) {
        SessionsKind.AGENT -> SessionsScreen(onOpen = onOpenSession, kindSwitch = switch)
        SessionsKind.CLOUD -> CloudSessionsScreen(onOpen = onOpenCloudSession, onNew = onNewCloudSession, onOpenTransfers = onOpenTransfers, kindSwitch = switch)
    }
}

/**
 * The person's cloud sessions, newest first, keyset-paged, each with its name, status (and preparation step), agent,
 * repository and age; New opens the new cloud session form. Loaded at each return and on refresh: the server publishes
 * no cloud session event.
 */
@Composable
fun CloudSessionsScreen(
    onOpen: (accountId: String, sessionId: String) -> Unit,
    onNew: (accountId: String) -> Unit,
    onOpenTransfers: (accountId: String) -> Unit = {},
    kindSwitch: (@Composable () -> Unit)? = null,
    viewModel: CloudSessionsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleStartEffect(viewModel) {
        viewModel.start()
        onStopOrDispose { viewModel.stop() }
    }
    val now = rememberNow()
    val colors = Ostrom.colors
    val account = state.account
    Column(Modifier.fillMaxSize().background(colors.background), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LargeTitle(stringResource(if (kindSwitch != null) Res.string.sessions_title else Res.string.cloud_sessions_title)) {
                if (account != null && account.signedIn && !state.unavailable) TransfersButton(account.id) { onOpenTransfers(account.id) }
                AppIconButton(PhosphorIcons.Regular.ArrowsClockwise, stringResource(Res.string.cloud_sessions_refresh), onClick = viewModel::reload,
                    modifier = Modifier.testTag("cloud-sessions-refresh"))
                if (account != null && account.signedIn && !state.unavailable) {
                    AppIconButton(PhosphorIcons.Regular.Plus, stringResource(Res.string.cloud_sessions_new), onClick = { onNew(account.id) }, kind = IconButtonKind.FILLED,
                        modifier = Modifier.testTag("cloud-sessions-new"))
                }
            }
            kindSwitch?.invoke()
            if (state.unavailable) Banner(stringResource(Res.string.cloud_sessions_unavailable), Tone.WARN, Modifier.testTag("cloud-sessions-unavailable"))
            state.error?.let { error ->
                Banner(error.words(), Tone.DANGER, Modifier.testTag("cloud-sessions-error"), action = {
                    AppButton(stringResource(Res.string.action_retry), onClick = viewModel::retry, kind = ButtonKind.SECONDARY, small = true, fill = false,
                        modifier = Modifier.testTag("cloud-sessions-retry"))
                })
            }
        }
        val listState = rememberLazyListState()
        val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 3 } == true } }
        LaunchedEffect(nearEnd, state.next) { if (nearEnd && state.error == null) viewModel.loadMore() }
        LazyColumn(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).testTag("cloud-sessions"),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.sessions, key = { it.id }) { session ->
                CloudSessionCard(session, now, showKind = state.remoteSessions) { account?.let { onOpen(it.id, session.id) } }
            }
            item(key = "end") {
                BusyRow(state.loading || state.loadingMore)
                if (state.empty && state.error == null && !state.unavailable) {
                    EmptyState(PhosphorIcons.Regular.Cloud, stringResource(Res.string.cloud_sessions_empty), stringResource(Res.string.cloud_sessions_empty_body),
                        Modifier.testTag("cloud-sessions-empty"))
                }
                if (state.next != null && !state.loadingMore && !state.loading) {
                    AppButton(stringResource(Res.string.action_load_more), onClick = viewModel::loadMore, kind = ButtonKind.GHOST, modifier = Modifier.testTag("cloud-sessions-more"))
                }
            }
        }
    }
}

/**
 * A session: its name and status, its agent and model, where it runs ([showKind], once the server has remote
 * sessions), its repository or, on a device, its folder, its age.
 */
@Composable
private fun CloudSessionCard(session: CloudSession, now: Long, showKind: Boolean, onClick: () -> Unit) {
    val colors = Ostrom.colors
    AppCard(Modifier.testTag("cloud-session-${session.id}"), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(session.title(), color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            CloudStatusChip(session, Modifier.testTag("cloud-session-status-${session.id}"))
        }
        if (session.preparing) {
            // A remote session waiting for its device is not preparing a workspace: the server's phase is worded.
            Text(if (session.waitingForHost) stringResource(Res.string.session_phase_waiting_host) else phaseWords(session.preparationPhase), color = colors.accentText,
                fontSize = 13.sp, modifier = Modifier.testTag("cloud-session-phase-${session.id}"))
        }
        Text(listOfNotNull(session.agent, session.model).joinToString(" · "), color = colors.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (showKind || session.remote) KindChips(session)
        // A remote session has no repository to clone: the folder it runs in tells it apart instead.
        val where = if (session.remote) session.host?.directoryName else session.recipe?.repositories?.firstOrNull()?.url ?: session.repo
        val age = session.createdAt?.let { stringResource(Res.string.cloud_sessions_created_ago, relativeTime(it, now)) }
        val meta = listOfNotNull(where?.removePrefix("https://github.com/"), age)
        if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MetaStyle, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** What a cloud session is called: its name, else its repository's, else its agent. */
internal fun CloudSession.title(): String = name?.takeIf { it.isNotBlank() } ?: repo?.substringAfterLast('/') ?: agent

/** The session's status, accented while it runs or is on its way, red when it failed. */
@Composable
internal fun CloudStatusChip(session: CloudSession, modifier: Modifier = Modifier) {
    val status = session.status
    val (words, tone) = when (status) {
        CloudSessionStatus.CREATING -> Res.string.cloud_status_creating to Tone.ACCENT
        CloudSessionStatus.RUNNING -> Res.string.cloud_status_running to Tone.ACCENT
        CloudSessionStatus.PAUSING -> Res.string.cloud_status_pausing to Tone.NEUTRAL
        CloudSessionStatus.PAUSED -> Res.string.cloud_status_paused to Tone.NEUTRAL
        CloudSessionStatus.RESUMING -> Res.string.cloud_status_resuming to Tone.ACCENT
        CloudSessionStatus.TRANSFERRING -> Res.string.cloud_status_transferring to Tone.ACCENT
        CloudSessionStatus.TERMINATED -> Res.string.cloud_status_terminated to Tone.MUTED
        CloudSessionStatus.FAILED -> Res.string.cloud_status_failed to Tone.DANGER
        CloudSessionStatus.UNKNOWN -> Res.string.cloud_status_unknown to Tone.MUTED
    }
    Chip(stringResource(words), modifier, tone = tone, dot = tone == Tone.ACCENT)
}

/** The preparation step in words; any step this app does not know reads as preparing the workspace. */
@Composable
internal fun phaseWords(phase: CloudPhase?): String = stringResource(
    when (phase) {
        CloudPhase.CONFIGURING_GIT -> Res.string.cloud_phase_configuring_git
        CloudPhase.CLONING_REPOSITORIES -> Res.string.cloud_phase_cloning_repositories
        CloudPhase.FETCHING_REPOSITORIES -> Res.string.cloud_phase_fetching_repositories
        CloudPhase.RUNNING_SETUP -> Res.string.cloud_phase_running_setup
        null -> Res.string.cloud_phase_other
    },
)
