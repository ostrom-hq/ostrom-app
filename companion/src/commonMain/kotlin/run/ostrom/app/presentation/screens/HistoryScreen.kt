// SPDX-License-Identifier: Apache-2.0
// Ported from Ostrom's React Native app, src/ui/screens/history/HistoryScreen.tsx; laid out like the activity screen.
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Receipt
import com.adamglin.phosphoricons.regular.Warning
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import run.ostrom.app.domain.models.CheckRun
import run.ostrom.app.domain.models.HistoryEntry
import run.ostrom.app.domain.models.ReceiptCheck
import run.ostrom.app.domain.models.ReceiptRejection
import run.ostrom.app.domain.models.ReceiptSummary
import run.ostrom.app.domain.models.RunOrigin
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.AppCard
import run.ostrom.app.presentation.components.Banner
import run.ostrom.app.presentation.components.ButtonKind
import run.ostrom.app.presentation.components.BusyRow
import run.ostrom.app.presentation.components.Chip
import run.ostrom.app.presentation.components.ContentWidth
import run.ostrom.app.presentation.components.EmptyState
import run.ostrom.app.presentation.components.KeepAtTopOnNewFirstRow
import run.ostrom.app.presentation.components.LargeTitle
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.components.rememberNow
import run.ostrom.app.presentation.theme.MetaStyle
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.viewmodels.HistoryViewModel
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.action_load_more
import run.ostrom.app.resources.action_retry
import run.ostrom.app.resources.history_approved_by
import run.ostrom.app.resources.history_checking
import run.ostrom.app.resources.history_decided_by
import run.ostrom.app.resources.history_empty
import run.ostrom.app.resources.history_empty_title
import run.ostrom.app.resources.history_error
import run.ostrom.app.resources.history_intro
import run.ostrom.app.resources.history_more_failed
import run.ostrom.app.resources.history_no_key
import run.ostrom.app.resources.history_origin_ci
import run.ostrom.app.resources.history_origin_isolated_runner
import run.ostrom.app.resources.history_origin_unknown
import run.ostrom.app.resources.history_origin_unverified
import run.ostrom.app.resources.history_origin_workstation_declared
import run.ostrom.app.resources.history_reason_bad_signature
import run.ostrom.app.resources.history_reason_digest_mismatch
import run.ostrom.app.resources.history_reason_malformed
import run.ostrom.app.resources.history_reason_missing_signature
import run.ostrom.app.resources.history_reason_unknown_algorithm
import run.ostrom.app.resources.history_reason_unknown_key
import run.ostrom.app.resources.history_rejected
import run.ostrom.app.resources.history_rejected_by
import run.ostrom.app.resources.history_self_approved
import run.ostrom.app.resources.history_signed
import run.ostrom.app.resources.history_stale
import run.ostrom.app.resources.history_title
import run.ostrom.app.resources.history_unavailable
import run.ostrom.app.resources.history_unreadable
import run.ostrom.app.resources.history_verified

/**
 * The organization's signed receipts, newest first: who decided, the result, the subject, when, and what this device
 * found of each signature. The first page loads at each return to the tab and on a pull; older pages as the list nears
 * its end. A refresh that fails keeps the receipts read before, under a banner that says they may be out of date.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: HistoryViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.start()
        onPauseOrDispose { }
    }
    val colors = Ostrom.colors
    val today = localEpochDay(rememberNow())
    Column(Modifier.fillMaxSize().background(colors.background), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LargeTitle(stringResource(Res.string.history_title))
            Text(stringResource(Res.string.history_intro), color = colors.muted, fontSize = 13.5.sp, lineHeight = 19.sp)
            if (state.stale) Banner(stringResource(Res.string.history_stale), Tone.DANGER, Modifier.testTag("history-stale"), action = {
                AppButton(stringResource(Res.string.action_retry), onClick = viewModel::refresh, kind = ButtonKind.SECONDARY, small = true, fill = false, modifier = Modifier.testTag("history-retry"))
            })
        }
        val listState = rememberLazyListState()
        val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 3 } == true } }
        LaunchedEffect(nearEnd, state.next, state.refreshing) { if (nearEnd && !state.moreFailed) viewModel.loadMore() }
        val keys = remember(state.entries) { rowKeys(state.entries) }
        KeepAtTopOnNewFirstRow(listState, keys.firstOrNull() ?: "end")
        PullToRefreshBox(
            isRefreshing = state.refreshing && state.loaded, onRefresh = viewModel::refresh,
            modifier = Modifier.weight(1f).widthIn(max = ContentWidth).fillMaxWidth(),
        ) {
            LazyColumn(
                Modifier.fillMaxSize().testTag("history"),
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(state.entries, key = { index, _ -> keys[index] }) { index, entry -> ReceiptRow(index, entry, today) }
                item(key = "end") {
                    BusyRow(state.loadingMore || (state.refreshing && !state.loaded))
                    if (state.loaded && state.entries.isEmpty() && !state.failed) EmptyState(
                        PhosphorIcons.Regular.Receipt, stringResource(Res.string.history_empty_title), stringResource(Res.string.history_empty), Modifier.testTag("history-empty"),
                    )
                    if (state.failed && state.entries.isEmpty() && !state.refreshing) EmptyState(
                        PhosphorIcons.Regular.Warning, stringResource(Res.string.history_title), stringResource(Res.string.history_error), Modifier.testTag("history-error"),
                        dashed = false, iconTint = colors.warn,
                    ) {
                        AppButton(stringResource(Res.string.action_retry), onClick = viewModel::refresh, modifier = Modifier.testTag("history-retry"))
                    }
                    if (state.moreFailed) Banner(stringResource(Res.string.history_more_failed), Tone.DANGER, Modifier.testTag("history-more-failed"), action = {
                        AppButton(stringResource(Res.string.action_retry), onClick = viewModel::retry, kind = ButtonKind.SECONDARY, small = true, fill = false, modifier = Modifier.testTag("history-more-retry"))
                    })
                    if (state.next != null && !state.loadingMore && !state.moreFailed) {
                        AppButton(stringResource(Res.string.action_load_more), onClick = viewModel::loadMore, kind = ButtonKind.GHOST, modifier = Modifier.testTag("history-more"))
                    }
                }
            }
        }
    }
}

/**
 * Each row's key: the receipt's id, which stays when a newer receipt comes first; a repeated id (a server bug) gets its
 * rank among them, and a receipt this app cannot read its position.
 */
private fun rowKeys(entries: List<HistoryEntry>): List<String> {
    val seen = HashMap<String, Int>()
    return entries.mapIndexed { index, entry ->
        val id = entry.summary?.id ?: return@mapIndexed "unreadable:$index"
        val rank = seen.getOrElse(id) { 0 }
        seen[id] = rank + 1
        if (rank == 0) "receipt:$id" else "receipt:$id#$rank"
    }
}

/** The tag of a receipt's row: its id, or its position when this app cannot read it. */
private fun rowTag(index: Int, entry: HistoryEntry) = "receipt-${entry.summary?.id ?: "unreadable-$index"}"

@Composable
private fun ReceiptRow(index: Int, entry: HistoryEntry, today: Long) {
    val colors = Ostrom.colors
    val summary = entry.summary
    AppCard(Modifier.testTag(rowTag(index, entry))) {
        if (summary == null) {
            Text(stringResource(Res.string.history_unreadable), color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        } else {
            val title = titleOf(summary)
            Text(if (summary.selfApproved) "$title · ${stringResource(Res.string.history_self_approved)}" else title, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(listOf(summary.subjectKind, summary.subjectId).filter { it.isNotBlank() }.joinToString(" "), style = MetaStyle, color = colors.muted)
            summary.at?.let { Text(formatEventTime(it, today), style = MetaStyle, color = colors.muted) }
            // The position first: two checks may share a label.
            entry.checkRuns.forEachIndexed { position, run -> CheckRunLine(run, Modifier.testTag("${rowTag(index, entry)}-run-$position")) }
        }
        CheckLine(entry.check)
    }
}

/** A result this app does not know is never shown raw inside a translated sentence. */
@Composable
private fun titleOf(summary: ReceiptSummary): String = when (summary.result) {
    null -> stringResource(Res.string.history_signed, summary.by)
    "approved" -> stringResource(Res.string.history_approved_by, summary.by)
    "rejected" -> stringResource(Res.string.history_rejected_by, summary.by)
    else -> stringResource(Res.string.history_decided_by, summary.by)
}

/** A check of the receipt and where it ran; the badge says whether that origin is proven, never whether it passed. */
@Composable
private fun CheckRunLine(run: CheckRun, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${run.label} · ${run.outcome}", color = Ostrom.colors.text, fontSize = 13.5.sp, modifier = Modifier.weight(1f, fill = false))
        Chip(stringResource(originText(run.origin)), tone = if (run.origin.proven) Tone.ACCENT else Tone.WARN, dot = run.origin.proven,
            modifier = Modifier.testTag(if (run.origin.proven) "run-proven" else "run-unproven"))
    }
}

private fun originText(origin: RunOrigin): StringResource = when (origin) {
    RunOrigin.WORKSTATION_DECLARED -> Res.string.history_origin_workstation_declared
    RunOrigin.ISOLATED_RUNNER -> Res.string.history_origin_isolated_runner
    RunOrigin.CI -> Res.string.history_origin_ci
    RunOrigin.UNKNOWN -> Res.string.history_origin_unknown
    RunOrigin.UNVERIFIED -> Res.string.history_origin_unverified
}

@Composable
private fun CheckLine(check: ReceiptCheck) {
    val colors = Ostrom.colors
    val (text, color, tag) = when (check) {
        ReceiptCheck.Checking -> Triple(stringResource(Res.string.history_checking), colors.muted, "check-checking")
        is ReceiptCheck.Verified -> Triple(stringResource(Res.string.history_verified, check.kid), colors.accentText, "check-verified")
        is ReceiptCheck.Rejected -> Triple(stringResource(Res.string.history_rejected, stringResource(reasonText(check.reason))), colors.danger, "check-rejected")
        ReceiptCheck.NoKey -> Triple(stringResource(Res.string.history_no_key), colors.muted, "check-no-key")
        ReceiptCheck.Unavailable -> Triple(stringResource(Res.string.history_unavailable), colors.muted, "check-unavailable")
    }
    Text(text, color = color, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag(tag))
}

private fun reasonText(reason: ReceiptRejection): StringResource = when (reason) {
    ReceiptRejection.MALFORMED -> Res.string.history_reason_malformed
    ReceiptRejection.MISSING_SIGNATURE -> Res.string.history_reason_missing_signature
    ReceiptRejection.UNKNOWN_ALGORITHM -> Res.string.history_reason_unknown_algorithm
    ReceiptRejection.UNKNOWN_KEY -> Res.string.history_reason_unknown_key
    ReceiptRejection.DIGEST_MISMATCH -> Res.string.history_reason_digest_mismatch
    ReceiptRejection.BAD_SIGNATURE -> Res.string.history_reason_bad_signature
}
