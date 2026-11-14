// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion presentation/screens/SessionScreen.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import com.adamglin.phosphoricons.regular.FlowArrow
import com.adamglin.phosphoricons.regular.Receipt
import org.jetbrains.compose.resources.stringResource
import run.ostrom.app.domain.models.AgentSession
import run.ostrom.app.domain.models.SessionGateAnswer
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.AppCard
import run.ostrom.app.presentation.components.AppIconButton
import run.ostrom.app.presentation.components.Banner
import run.ostrom.app.presentation.components.ButtonKind
import run.ostrom.app.presentation.components.BusyRow
import run.ostrom.app.presentation.components.Chip
import run.ostrom.app.presentation.components.ContentWidth
import run.ostrom.app.presentation.components.NavBar
import run.ostrom.app.presentation.components.SectionTitle
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.components.formatUsd
import run.ostrom.app.presentation.components.rememberNow
import run.ostrom.app.presentation.theme.MetaStyle
import run.ostrom.app.presentation.theme.MonospaceStyle
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.viewmodels.SessionViewModel
import run.ostrom.app.presentation.viewmodels.words
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.action_retry
import run.ostrom.app.resources.session_cost
import run.ostrom.app.resources.session_ended
import run.ostrom.app.resources.session_ended_by
import run.ostrom.app.resources.session_exit_code
import run.ostrom.app.resources.session_gate_answer
import run.ostrom.app.resources.session_gates
import run.ostrom.app.resources.session_last_seen
import run.ostrom.app.resources.session_open_history
import run.ostrom.app.resources.session_open_run
import run.ostrom.app.resources.session_receipt
import run.ostrom.app.resources.session_receipt_hint
import run.ostrom.app.resources.session_refresh
import run.ostrom.app.resources.session_segment
import run.ostrom.app.resources.session_started
import run.ostrom.app.resources.session_title
import run.ostrom.app.resources.session_todo
import run.ostrom.app.resources.sessions_no_repo
import run.ostrom.app.resources.sessions_title
import run.ostrom.app.resources.sessions_tools

/**
 * One session's record: its state, agent, model and person, its times, cost and tools, the answers to its gates, a
 * link to its receipt in the History tab (where the server serves receipts) and to its workflow run, and its todo,
 * kept current by the app's one feed ([TodoPanel]).
 */
@Composable
fun SessionScreen(
    viewModel: SessionViewModel,
    onBack: (() -> Unit)?,
    onOpenHistory: () -> Unit,
    onOpenRun: (runId: String) -> Unit,
    backLabel: String = stringResource(Res.string.sessions_title),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleStartEffect(viewModel) {
        viewModel.start()
        onStopOrDispose { viewModel.stop() }
    }
    val now = rememberNow()
    val today = localEpochDay(now)
    val colors = Ostrom.colors
    val details = state.details
    Column(Modifier.fillMaxSize().background(colors.background), horizontalAlignment = Alignment.CenterHorizontally) {
        NavBar(backLabel, onBack, details?.session?.repo ?: stringResource(Res.string.session_title)) {
            AppIconButton(PhosphorIcons.Regular.ArrowsClockwise, stringResource(Res.string.session_refresh), onClick = viewModel::load, enabled = !state.loading,
                modifier = Modifier.testTag("session-refresh"))
        }
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)
                .testTag("session-page"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { error ->
                Banner(error.words(), Tone.DANGER, Modifier.testTag("session-error"), action = if (!state.gone) {
                    { AppButton(stringResource(Res.string.action_retry), onClick = viewModel::load, kind = ButtonKind.SECONDARY, small = true, fill = false, modifier = Modifier.testTag("session-retry")) }
                } else null)
            }
            BusyRow(state.loading && details == null)
            if (details != null) {
                val session = details.session
                Overview(session, today)
                val receipt = session.receiptId
                val run = session.runId
                if (receipt != null || (run != null && details.runsServed)) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (receipt != null && details.historyServed) {
                        AppButton(stringResource(Res.string.session_open_history), onClick = onOpenHistory, kind = ButtonKind.SECONDARY, small = true, fill = false,
                            icon = PhosphorIcons.Regular.Receipt, modifier = Modifier.testTag("session-open-history"))
                    }
                    if (run != null && details.runsServed) {
                        AppButton(stringResource(Res.string.session_open_run), onClick = { onOpenRun(run) }, kind = ButtonKind.SECONDARY, small = true, fill = false,
                            icon = PhosphorIcons.Regular.FlowArrow, modifier = Modifier.testTag("session-open-run"))
                    }
                }
                if (receipt != null) {
                    SelectionContainer {
                        Text(stringResource(Res.string.session_receipt, receipt), style = MetaStyle, color = colors.muted, modifier = Modifier.testTag("session-receipt"))
                    }
                    if (details.historyServed) Text(stringResource(Res.string.session_receipt_hint), fontSize = 12.sp, color = colors.muted)
                }
                if (session.gateAnswers.isNotEmpty()) {
                    SectionTitle(stringResource(Res.string.session_gates))
                    session.gateAnswers.forEach { GateAnswerRow(it, today) }
                }
                if (details.todosServed) {
                    SectionTitle(stringResource(Res.string.session_todo))
                    TodoPanel(state.todo, state.todoLoaded, state.todoReconnecting, now)
                }
            }
        }
    }
}

@Composable
private fun Overview(session: AgentSession, today: Long) {
    val colors = Ostrom.colors
    val language = Locale.current.language
    AppCard(Modifier.testTag("session-overview")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(session.repo ?: stringResource(Res.string.sessions_no_repo), color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            SessionStateChip(session, Modifier.testTag("session-state"))
        }
        Text(listOfNotNull(session.agent, session.model).joinToString(" · "), color = colors.text, fontSize = 14.sp)
        Text(session.dev, style = MetaStyle, color = colors.muted)
        session.startedAt?.let { Text(stringResource(Res.string.session_started, formatEventTime(it, today)), style = MetaStyle, color = colors.muted) }
        session.endedAt?.let { Text(stringResource(Res.string.session_ended, formatEventTime(it, today)), style = MetaStyle, color = colors.muted, modifier = Modifier.testTag("session-ended")) }
        session.endedBy?.let { Text(stringResource(Res.string.session_ended_by, it), style = MetaStyle, color = colors.muted) }
        if (session.endedAt == null) session.lastSeenAt?.let { Text(stringResource(Res.string.session_last_seen, formatEventTime(it, today)), style = MetaStyle, color = colors.muted) }
        val figures = listOfNotNull(
            session.costUsd?.let { stringResource(Res.string.session_cost, formatUsd(it, language)) },
            session.tools?.let { stringResource(Res.string.sessions_tools, it) },
            session.exitCode?.let { stringResource(Res.string.session_exit_code, it) },
            session.segment?.takeIf { it > 1 }?.let { stringResource(Res.string.session_segment, it) },
        )
        if (figures.isNotEmpty()) Text(figures.joinToString(" · "), style = MetaStyle, color = colors.text, modifier = Modifier.testTag("session-figures"))
        SelectionContainer { Text(session.id, style = MonospaceStyle, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun GateAnswerRow(answer: SessionGateAnswer, today: Long) {
    val colors = Ostrom.colors
    AppCard(Modifier.testTag("session-gate-${answer.gateId}")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(answer.actionClass, style = MonospaceStyle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Chip(answer.outcome, tone = when (answer.outcome) { "approved" -> Tone.ACCENT; "denied", "rejected" -> Tone.DANGER; else -> Tone.NEUTRAL })
        }
        val at = answer.at?.let { formatEventTime(it, today) }
        Text(if (at != null) stringResource(Res.string.session_gate_answer, answer.by, at) else answer.by, style = MetaStyle, color = colors.muted)
    }
}
