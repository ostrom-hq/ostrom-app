// SPDX-License-Identifier: Apache-2.0
// Laid out like TeamCode's companion list screens (Apache-2.0); see NOTICE.
package run.ostrom.app.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Warning
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import run.ostrom.app.domain.models.Breakdown
import run.ostrom.app.domain.models.BreakdownKind
import run.ostrom.app.domain.models.Budget
import run.ostrom.app.domain.models.BudgetPeriod
import run.ostrom.app.domain.models.ExceededLine
import run.ostrom.app.domain.models.ExceededWho
import run.ostrom.app.domain.models.SpendPeriod
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.AppCard
import run.ostrom.app.presentation.components.Banner
import run.ostrom.app.presentation.components.ButtonKind
import run.ostrom.app.presentation.components.BusyRow
import run.ostrom.app.presentation.components.ContentWidth
import run.ostrom.app.presentation.components.EmptyState
import run.ostrom.app.presentation.components.LargeTitle
import run.ostrom.app.presentation.components.SectionTitle
import run.ostrom.app.presentation.components.Segmented
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.components.formatUsd
import run.ostrom.app.presentation.theme.MetaStyle
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.viewmodels.SpendingViewModel
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.action_retry
import run.ostrom.app.resources.spending_by_agent
import run.ostrom.app.resources.spending_by_person
import run.ostrom.app.resources.spending_by_repo
import run.ostrom.app.resources.spending_error
import run.ostrom.app.resources.spending_exceeded_goes_on
import run.ostrom.app.resources.spending_exceeded_me_day
import run.ostrom.app.resources.spending_exceeded_me_month
import run.ostrom.app.resources.spending_exceeded_member_day
import run.ostrom.app.resources.spending_exceeded_member_month
import run.ostrom.app.resources.spending_exceeded_organization_day
import run.ostrom.app.resources.spending_exceeded_organization_month
import run.ostrom.app.resources.spending_exceeded_someone_day
import run.ostrom.app.resources.spending_exceeded_someone_month
import run.ostrom.app.resources.spending_exceeded_stops_day
import run.ostrom.app.resources.spending_exceeded_stops_month
import run.ostrom.app.resources.spending_exceeded_title
import run.ostrom.app.resources.spending_load_failed
import run.ostrom.app.resources.spending_none
import run.ostrom.app.resources.spending_of_budget
import run.ostrom.app.resources.spending_period_month
import run.ostrom.app.resources.spending_period_week
import run.ostrom.app.resources.spending_sessions
import run.ostrom.app.resources.spending_title

/**
 * What the organization's agents cost this week or this month: the total against the budget (amber from 80 %, the only
 * use of amber), the budgets passed, and the breakdowns by agent, repository and person, biggest first. Loaded at each
 * return to the tab.
 */
@Composable
fun SpendingScreen(viewModel: SpendingViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.start()
        onPauseOrDispose { }
    }
    val colors = Ostrom.colors
    val language = Locale.current.language
    val report = state.report
    Column(Modifier.fillMaxSize().background(colors.background), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LargeTitle(stringResource(Res.string.spending_title))
            Segmented(
                listOf(SpendPeriod.WEEK to stringResource(Res.string.spending_period_week), SpendPeriod.MONTH to stringResource(Res.string.spending_period_month)),
                state.period, viewModel::setPeriod, Modifier.fillMaxWidth(), tag = { "spending-period-${it.wire}" },
            )
            if (state.error && report != null) Banner(stringResource(Res.string.spending_load_failed), Tone.DANGER, Modifier.testTag("spending-error"), action = {
                AppButton(stringResource(Res.string.action_retry), onClick = viewModel::retry, kind = ButtonKind.SECONDARY, small = true, fill = false, modifier = Modifier.testTag("spending-retry"))
            })
        }
        LazyColumn(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).testTag("spending"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (report == null) {
                item(key = "state") {
                    BusyRow(state.loading)
                    if (state.error) EmptyState(
                        PhosphorIcons.Regular.Warning, stringResource(Res.string.spending_title), stringResource(Res.string.spending_error), Modifier.testTag("spending-error-state"),
                        dashed = false, iconTint = colors.warn,
                    ) {
                        AppButton(stringResource(Res.string.action_retry), onClick = viewModel::retry, modifier = Modifier.testTag("spending-retry"))
                    }
                }
                return@LazyColumn
            }
            item(key = "total") {
                val budget = report.budget()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        formatUsd(report.totalUsd, language), color = if (budget?.warning == true) colors.warn else colors.text,
                        fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("spending-total"),
                    )
                    budget?.let { BudgetBar(it, language) }
                }
            }
            val exceeded = report.exceededLines(state.account?.login)
            if (exceeded.isNotEmpty()) item(key = "exceeded") { ExceededSection(exceeded, language) }
            items(report.breakdowns(), key = { it.kind.name }) { BreakdownSection(it, language) }
        }
    }
}

@Composable
private fun Bar(share: Double, color: Color) {
    val colors = Ostrom.colors
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.panelAlt)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(share.coerceIn(0.0, 1.0).toFloat()).clip(RoundedCornerShape(3.dp)).background(color))
    }
}

@Composable
private fun BudgetBar(budget: Budget, language: String) {
    val colors = Ostrom.colors
    Column(Modifier.testTag("spending-budget"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Bar(budget.ratio, if (budget.warning) colors.warn else colors.muted)
        Text(
            stringResource(Res.string.spending_of_budget, (budget.ratio * 100).let { kotlin.math.round(it).toInt() }, formatUsd(budget.usd, language)),
            style = MetaStyle, color = if (budget.warning) colors.warn else colors.muted,
        )
    }
}

@Composable
private fun ExceededSection(lines: List<ExceededLine>, language: String) {
    val colors = Ostrom.colors
    AppCard(Modifier.testTag("spending-exceeded"), borderColor = colors.warn) {
        Text(stringResource(Res.string.spending_exceeded_title), color = colors.warn, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        lines.forEach { line ->
            val usd = formatUsd(line.usd, language)
            val day = line.period == BudgetPeriod.DAY
            val who = when (val whose = line.who) {
                ExceededWho.Me -> stringResource(if (day) Res.string.spending_exceeded_me_day else Res.string.spending_exceeded_me_month, usd)
                ExceededWho.Organization -> stringResource(if (day) Res.string.spending_exceeded_organization_day else Res.string.spending_exceeded_organization_month, usd)
                is ExceededWho.Member -> whose.name?.let { stringResource(if (day) Res.string.spending_exceeded_member_day else Res.string.spending_exceeded_member_month, it, usd) }
                    ?: stringResource(if (day) Res.string.spending_exceeded_someone_day else Res.string.spending_exceeded_someone_month, usd)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(who, color = colors.text, fontSize = 13.sp)
                Text(
                    stringResource(if (!line.stops) Res.string.spending_exceeded_goes_on else if (day) Res.string.spending_exceeded_stops_day else Res.string.spending_exceeded_stops_month),
                    style = MetaStyle, color = colors.muted,
                )
            }
        }
    }
}

@Composable
private fun BreakdownSection(breakdown: Breakdown, language: String) {
    val colors = Ostrom.colors
    Column(Modifier.testTag("spending-${breakdown.kind.name.lowercase()}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle(stringResource(when (breakdown.kind) {
            BreakdownKind.AGENT -> Res.string.spending_by_agent
            BreakdownKind.REPOSITORY -> Res.string.spending_by_repo
            BreakdownKind.PERSON -> Res.string.spending_by_person
        }))
        AppCard {
            if (breakdown.lines.isEmpty()) Text(stringResource(Res.string.spending_none), style = MetaStyle, color = colors.muted)
            breakdown.lines.forEach { (line, share) ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(line.label, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text(formatUsd(line.usd, language), color = colors.text, fontSize = 14.sp)
                    }
                    Bar(share, colors.muted)
                    Text(stringResource(Res.string.spending_sessions, line.sessions), style = MetaStyle, color = colors.muted)
                }
            }
        }
    }
}
