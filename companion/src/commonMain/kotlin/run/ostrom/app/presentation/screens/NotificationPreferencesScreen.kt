// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion presentation/screens/NotificationPreferencesScreen.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import run.ostrom.app.domain.models.NotificationToggle
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.Banner
import run.ostrom.app.presentation.components.BusyRow
import run.ostrom.app.presentation.components.ButtonKind
import run.ostrom.app.presentation.components.ContentWidth
import run.ostrom.app.presentation.components.Group
import run.ostrom.app.presentation.components.GroupDivider
import run.ostrom.app.presentation.components.GroupRow
import run.ostrom.app.presentation.components.NavBar
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.viewmodels.NotificationPreferencesViewModel
import run.ostrom.app.presentation.viewmodels.words
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.action_retry
import run.ostrom.app.resources.notifications_decisions
import run.ostrom.app.resources.notifications_note
import run.ostrom.app.resources.notifications_quiet_hours
import run.ostrom.app.resources.notifications_quiet_hours_range
import run.ostrom.app.resources.notifications_requests
import run.ostrom.app.resources.notifications_save_failed
import run.ostrom.app.resources.notifications_spending
import run.ostrom.app.resources.notifications_title
import run.ostrom.app.resources.settings_title

/**
 * What the person is notified of on their phones, one switch per kind, and quiet hours. The server keeps them for the
 * person: they hold on all their phones, whichever device changed them.
 */
@Composable
fun NotificationPreferencesScreen(viewModel: NotificationPreferencesViewModel, onBack: (() -> Unit)?) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = Ostrom.colors
    Column(Modifier.fillMaxSize().background(colors.background), horizontalAlignment = Alignment.CenterHorizontally) {
        NavBar(stringResource(Res.string.settings_title), onBack, stringResource(Res.string.notifications_title))
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp).testTag("notifications"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { error ->
                Banner(error.words(), Tone.DANGER, Modifier.testTag("notifications-error"), action = {
                    AppButton(stringResource(Res.string.action_retry), onClick = viewModel::refresh, kind = ButtonKind.SECONDARY, small = true, fill = false,
                        modifier = Modifier.testTag("notifications-retry"))
                })
            }
            if (state.saveFailed) Banner(stringResource(Res.string.notifications_save_failed), Tone.DANGER, Modifier.testTag("notifications-save-failed"))
            BusyRow(state.loading && state.preferences == null)
            state.preferences?.let { preferences ->
                Group(Modifier.testTag("notifications-switches")) {
                    NotificationToggle.entries.forEach { toggle ->
                        GroupRow {
                            Text(stringResource(toggle.label()), color = colors.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Switch(preferences[toggle], { viewModel.toggle(toggle, it) }, enabled = !state.saving,
                                modifier = Modifier.testTag("notifications-${toggle.name.lowercase()}"))
                        }
                        GroupDivider()
                    }
                    GroupRow {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(Res.string.notifications_quiet_hours), color = colors.text, fontSize = 15.sp)
                            preferences.quietHours?.let { quiet ->
                                Text(
                                    stringResource(Res.string.notifications_quiet_hours_range, quiet.start, quiet.end, quiet.timeZone),
                                    color = colors.muted, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.testTag("notifications-quiet-range"),
                                )
                            }
                        }
                        Switch(preferences.quietHours != null, viewModel::setQuietHours, enabled = !state.saving, modifier = Modifier.testTag("notifications-quiet"))
                    }
                }
                Text(stringResource(Res.string.notifications_note), color = colors.muted, fontSize = 12.5.sp, lineHeight = 17.sp)
            }
        }
    }
}

private fun NotificationToggle.label(): StringResource = when (this) {
    NotificationToggle.REQUESTS -> Res.string.notifications_requests
    NotificationToggle.DECISIONS -> Res.string.notifications_decisions
    NotificationToggle.SPENDING -> Res.string.notifications_spending
}
