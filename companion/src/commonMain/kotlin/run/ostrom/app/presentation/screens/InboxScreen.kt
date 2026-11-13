// SPDX-License-Identifier: AGPL-3.0-only
// Shaped like TeamCode's companion WaitsScreen.kt and SessionsScreen.kt (Apache-2.0); see NOTICE.
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.adamglin.phosphoricons.regular.ArrowClockwise
import com.adamglin.phosphoricons.regular.CheckCircle
import com.adamglin.phosphoricons.regular.Warning
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import run.ostrom.app.domain.models.Approval
import run.ostrom.app.domain.models.ApprovalStatus
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.AppCard
import run.ostrom.app.presentation.components.AppIconButton
import run.ostrom.app.presentation.components.Banner
import run.ostrom.app.presentation.components.BusyRow
import run.ostrom.app.presentation.components.ButtonKind
import run.ostrom.app.presentation.components.Chip
import run.ostrom.app.presentation.components.ContentWidth
import run.ostrom.app.presentation.components.EmptyState
import run.ostrom.app.presentation.components.LargeTitle
import run.ostrom.app.presentation.components.SectionTitle
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.theme.MetaStyle
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.viewmodels.InboxViewModel
import run.ostrom.app.presentation.viewmodels.words
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.approvals_choose_organization
import run.ostrom.app.resources.approvals_empty_body
import run.ostrom.app.resources.approvals_empty_title
import run.ostrom.app.resources.approvals_error_title
import run.ostrom.app.resources.approvals_pending
import run.ostrom.app.resources.approvals_recent
import run.ostrom.app.resources.approvals_refresh
import run.ostrom.app.resources.approvals_retry
import run.ostrom.app.resources.approvals_stale
import run.ostrom.app.resources.approvals_status_approved
import run.ostrom.app.resources.approvals_status_expired
import run.ostrom.app.resources.approvals_status_pending
import run.ostrom.app.resources.approvals_status_rejected
import run.ostrom.app.resources.approvals_status_unknown
import run.ostrom.app.resources.approvals_title
import run.ostrom.app.resources.approvals_yours

/**
 * The approvals inbox: what waits for the person's yes, then what was answered lately. A row only opens its request
 * ([onOpen]); the answer is given there, after reading it. The list is read again when the screen shows, on a pull or
 * with the refresh button; a failed refresh keeps the last list and says it may be out of date.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onOpen: (accountId: String, id: String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: InboxViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val colors = Ostrom.colors
    val open = { approval: Approval -> state.accountId?.let { onOpen(it, approval.id) } ?: Unit }
    Column(Modifier.fillMaxSize().background(colors.background), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LargeTitle(stringResource(Res.string.approvals_title)) {
                AppIconButton(PhosphorIcons.Regular.ArrowClockwise, stringResource(Res.string.approvals_refresh), onClick = viewModel::refresh,
                    enabled = !state.refreshing, modifier = Modifier.testTag("inbox-refresh"))
            }
            val error = state.error
            if (state.stale && error != null) {
                Banner(stringResource(Res.string.approvals_stale) + "\n" + error.words(), Tone.DANGER, Modifier.testTag("inbox-stale")) {
                    AppButton(stringResource(Res.string.approvals_retry), onClick = viewModel::refresh, kind = ButtonKind.SECONDARY, small = true, fill = false)
                }
                if (state.orgRequired) OrganizationButton(onOpenSettings)
            }
        }
        PullToRefreshBox(isRefreshing = state.refreshing && state.loaded, onRefresh = viewModel::refresh, modifier = Modifier.weight(1f).widthIn(max = ContentWidth).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize().testTag("inbox"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.pending.isNotEmpty()) {
                    item(key = "pending-title") { SectionTitle(stringResource(Res.string.approvals_pending)) }
                    items(state.pending, key = { "pending-${it.id}" }) { RequestRow(it, onOpen = { open(it) }) }
                }
                item(key = "state") {
                    val failure = state.error
                    when {
                        !state.loaded && failure == null -> BusyRow(true)
                        !state.loaded && failure != null -> EmptyState(
                            PhosphorIcons.Regular.Warning, stringResource(Res.string.approvals_error_title), failure.words(), Modifier.testTag("inbox-error"),
                            dashed = false, iconTint = colors.warn,
                        ) {
                            AppButton(stringResource(Res.string.approvals_retry), onClick = viewModel::refresh, modifier = Modifier.testTag("inbox-retry"))
                            if (state.orgRequired) OrganizationButton(onOpenSettings)
                        }
                        state.pending.isEmpty() -> EmptyState(
                            PhosphorIcons.Regular.CheckCircle, stringResource(Res.string.approvals_empty_title), stringResource(Res.string.approvals_empty_body), Modifier.testTag("inbox-empty"),
                        )
                    }
                }
                if (state.recent.isNotEmpty()) {
                    item(key = "recent-title") { SectionTitle(stringResource(Res.string.approvals_recent), Modifier.padding(top = 10.dp)) }
                    items(state.recent, key = { "recent-${it.id}" }) { RequestRow(it, onOpen = { open(it) }) }
                }
            }
        }
    }
}

@Composable
private fun OrganizationButton(onOpenSettings: () -> Unit) {
    AppButton(stringResource(Res.string.approvals_choose_organization), onClick = onOpenSettings, kind = ButtonKind.SECONDARY, modifier = Modifier.testTag("inbox-choose-organization"))
}

/** One request: its state, what is asked, where and by whom. A pending one is outlined in the accent. */
@Composable
private fun RequestRow(approval: Approval, onOpen: () -> Unit) {
    val colors = Ostrom.colors
    AppCard(Modifier.testTag("request-${approval.id}"), borderColor = if (approval.open) colors.accentDim else colors.line, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(approval.status)
            if (approval.viewer.self) Chip(stringResource(Res.string.approvals_yours), tone = Tone.MUTED)
        }
        Text(approval.title, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val detail = listOfNotNull(approval.repo, approval.askedBy.firstOrNull()).filter { it.isNotBlank() }.joinToString(" · ")
        if (detail.isNotEmpty()) Text(detail, style = MetaStyle, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The state of a request in a pill: the accent while it waits for a human. */
@Composable
internal fun StatusChip(status: ApprovalStatus, modifier: Modifier = Modifier) {
    val (label, tone) = when (status) {
        ApprovalStatus.PENDING -> Res.string.approvals_status_pending to Tone.ACCENT
        ApprovalStatus.APPROVED -> Res.string.approvals_status_approved to Tone.NEUTRAL
        ApprovalStatus.REJECTED -> Res.string.approvals_status_rejected to Tone.DANGER
        ApprovalStatus.EXPIRED -> Res.string.approvals_status_expired to Tone.MUTED
        ApprovalStatus.UNKNOWN -> Res.string.approvals_status_unknown to Tone.MUTED
    }
    Chip(stringResource(label), modifier.testTag("status"), tone = tone, dot = status == ApprovalStatus.PENDING)
}
