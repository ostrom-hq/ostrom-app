// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowsClockwise
import org.jetbrains.compose.resources.stringResource
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.AppIconButton
import run.ostrom.app.presentation.components.Banner
import run.ostrom.app.presentation.components.ButtonKind
import run.ostrom.app.presentation.components.BusyRow
import run.ostrom.app.presentation.components.ContentWidth
import run.ostrom.app.presentation.components.NavBar
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.components.rememberNow
import run.ostrom.app.presentation.theme.MonospaceStyle
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.viewmodels.ReceiptViewModel
import run.ostrom.app.presentation.viewmodels.words
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.action_retry
import run.ostrom.app.resources.receipt_refresh
import run.ostrom.app.resources.receipt_title
import run.ostrom.app.resources.session_title

/**
 * One signed receipt, opened by its id from a session's record: what the History tab shows of it (who decided, the
 * result, the subject, when, its check runs), and what this device found of its signature.
 */
@Composable
fun ReceiptScreen(viewModel: ReceiptViewModel, onBack: (() -> Unit)?, receiptId: String, backLabel: String = stringResource(Res.string.session_title)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = Ostrom.colors
    val today = localEpochDay(rememberNow())
    Column(Modifier.fillMaxSize().background(colors.background), horizontalAlignment = Alignment.CenterHorizontally) {
        NavBar(backLabel, onBack, stringResource(Res.string.receipt_title)) {
            AppIconButton(PhosphorIcons.Regular.ArrowsClockwise, stringResource(Res.string.receipt_refresh), onClick = viewModel::load, enabled = !state.loading,
                modifier = Modifier.testTag("receipt-refresh"))
        }
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)
                .testTag("receipt-page"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { error ->
                Banner(error.words(), Tone.DANGER, Modifier.testTag("receipt-error"), action = if (!state.gone) {
                    { AppButton(stringResource(Res.string.action_retry), onClick = viewModel::load, kind = ButtonKind.SECONDARY, small = true, fill = false, modifier = Modifier.testTag("receipt-retry")) }
                } else null)
            }
            BusyRow(state.loading && state.entry == null)
            state.entry?.let { entry ->
                ReceiptRow(0, entry, today)
                SelectionContainer { Text(receiptId, style = MonospaceStyle, color = colors.muted) }
            }
        }
    }
}
