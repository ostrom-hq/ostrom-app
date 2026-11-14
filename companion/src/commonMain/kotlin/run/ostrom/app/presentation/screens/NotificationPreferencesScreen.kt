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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import run.ostrom.app.domain.models.NotificationChannels
import run.ostrom.app.domain.models.NotificationKind
import run.ostrom.app.domain.models.NotificationToggle
import run.ostrom.app.domain.models.PushTestOutcome
import run.ostrom.app.domain.models.PushTestPhone
import run.ostrom.app.domain.models.PushTestStatus
import run.ostrom.app.domain.models.visibleCode
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.Banner
import run.ostrom.app.presentation.components.BusyRow
import run.ostrom.app.presentation.components.ButtonKind
import run.ostrom.app.presentation.components.ContentWidth
import run.ostrom.app.presentation.components.Group
import run.ostrom.app.presentation.components.GroupDivider
import run.ostrom.app.presentation.components.GroupRow
import run.ostrom.app.presentation.components.NavBar
import run.ostrom.app.presentation.components.SectionTitle
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.viewmodels.NotificationPreferencesViewModel
import run.ostrom.app.presentation.viewmodels.NotificationSettingsUiState
import run.ostrom.app.presentation.viewmodels.NotificationSettingsViewModel
import run.ostrom.app.presentation.viewmodels.words
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.action_retry
import run.ostrom.app.resources.notifications_channel_other
import run.ostrom.app.resources.notifications_channel_push
import run.ostrom.app.resources.notifications_decisions
import run.ostrom.app.resources.notifications_kind_session_failed
import run.ostrom.app.resources.notifications_kind_session_failed_hint
import run.ostrom.app.resources.notifications_kind_turn_completed
import run.ostrom.app.resources.notifications_kind_turn_completed_hint
import run.ostrom.app.resources.notifications_kind_turn_failed
import run.ostrom.app.resources.notifications_kind_turn_failed_hint
import run.ostrom.app.resources.notifications_kind_waiting
import run.ostrom.app.resources.notifications_kind_waiting_hint
import run.ostrom.app.resources.notifications_no_channel
import run.ostrom.app.resources.notifications_note
import run.ostrom.app.resources.notifications_quiet_hours
import run.ostrom.app.resources.notifications_quiet_hours_range
import run.ostrom.app.resources.notifications_requests
import run.ostrom.app.resources.notifications_requests_section
import run.ostrom.app.resources.notifications_save_failed
import run.ostrom.app.resources.notifications_spending
import run.ostrom.app.resources.notifications_test_button
import run.ostrom.app.resources.notifications_test_hint
import run.ostrom.app.resources.notifications_test_no_phone
import run.ostrom.app.resources.notifications_test_off
import run.ostrom.app.resources.notifications_test_phone
import run.ostrom.app.resources.notifications_test_phone_code
import run.ostrom.app.resources.notifications_test_section
import run.ostrom.app.resources.notifications_test_sent
import run.ostrom.app.resources.notifications_test_undelivered
import run.ostrom.app.resources.notifications_title
import run.ostrom.app.resources.push_test_status_other
import run.ostrom.app.resources.push_test_status_rejected
import run.ostrom.app.resources.push_test_status_retry
import run.ostrom.app.resources.push_test_status_sent
import run.ostrom.app.resources.push_test_status_unregistered
import run.ostrom.app.resources.settings_title

/**
 * What the person is notified of: one switch per kind on each channel the server offers ([settings], TeamCode's
 * Notifications screen), the requests and budgets they hear about with quiet hours ([viewModel]), and a test push to
 * their own phones when the server allows it. The server keeps them for the person: they hold on all their phones,
 * whichever device changed them.
 */
@Composable
fun NotificationPreferencesScreen(viewModel: NotificationPreferencesViewModel, settings: NotificationSettingsViewModel, onBack: (() -> Unit)?) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val channels by settings.uiState.collectAsStateWithLifecycle()
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
            ChannelSwitches(channels, settings)
            if (state.saveFailed) Banner(stringResource(Res.string.notifications_save_failed), Tone.DANGER, Modifier.testTag("notifications-save-failed"))
            BusyRow(state.loading && state.preferences == null)
            state.preferences?.let { preferences ->
                SectionTitle(stringResource(Res.string.notifications_requests_section))
                Group(Modifier.testTag("notifications-switches")) {
                    NotificationToggle.entries.forEach { toggle ->
                        GroupRow {
                            Text(stringResource(toggle.label()), color = colors.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Switch(preferences[toggle], { viewModel.toggle(toggle, it) }, enabled = !state.saving && !state.loading,
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
                        Switch(preferences.quietHours != null, viewModel::setQuietHours, enabled = !state.saving && !state.loading, modifier = Modifier.testTag("notifications-quiet"))
                    }
                }
                Text(stringResource(Res.string.notifications_note), color = colors.muted, fontSize = 12.5.sp, lineHeight = 17.sp)
            }
            if (channels.canTest && channels.settings != null) PushTest(channels, settings)
        }
    }
}

/** One section per channel the server offers, a switch per kind; a refused change goes back and says why. */
@Composable
private fun ChannelSwitches(state: NotificationSettingsUiState, viewModel: NotificationSettingsViewModel) {
    val colors = Ostrom.colors
    state.error?.let { error ->
        Banner(error.words(), Tone.DANGER, Modifier.testTag("notifications-settings-error"), action = if (state.settings == null) {
            {
                AppButton(stringResource(Res.string.action_retry), onClick = viewModel::refresh, kind = ButtonKind.SECONDARY, small = true, fill = false,
                    modifier = Modifier.testTag("notifications-settings-retry"))
            }
        } else null)
    }
    val settings = state.settings ?: return
    if (settings.channels.isEmpty()) {
        Text(stringResource(Res.string.notifications_no_channel), color = colors.muted, fontSize = 12.5.sp, lineHeight = 17.sp,
            modifier = Modifier.testTag("notifications-no-channel"))
    }
    settings.channels.forEach { channel ->
        SectionTitle(if (channel == NotificationChannels.PUSH) stringResource(Res.string.notifications_channel_push) else stringResource(Res.string.notifications_channel_other, channel))
        Group(Modifier.testTag("notifications-channel-$channel")) {
            NotificationKind.entries.forEachIndexed { index, kind ->
                if (index > 0) GroupDivider()
                GroupRow {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(kind.title()), color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(stringResource(kind.hint()), color = colors.muted, fontSize = 12.5.sp, lineHeight = 17.sp)
                    }
                    Switch(
                        settings.isOn(channel, kind), { viewModel.toggle(channel, kind, it) },
                        enabled = !state.loading && state.saving.none { it.channel == channel && it.kind == kind },
                        modifier = Modifier.testTag("notifications-switch-$channel-${kind.wire}"),
                    )
                }
            }
        }
    }
}

/** "Send a test to my phone", and how each phone took it. */
@Composable
private fun PushTest(state: NotificationSettingsUiState, viewModel: NotificationSettingsViewModel) {
    val colors = Ostrom.colors
    SectionTitle(stringResource(Res.string.notifications_test_section))
    Group {
        GroupRow {
            Text(stringResource(Res.string.notifications_test_hint), color = colors.muted, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
        }
        GroupDivider()
        GroupRow {
            AppButton(stringResource(Res.string.notifications_test_button), onClick = viewModel::test, kind = ButtonKind.SECONDARY, busy = state.testing,
                enabled = !state.testing, modifier = Modifier.weight(1f).testTag("notifications-test"))
        }
    }
    when (val outcome = state.test) {
        null -> Unit
        is PushTestOutcome.Sent -> {
            if (outcome.delivered > 0) {
                Banner(stringResource(Res.string.notifications_test_sent, outcome.delivered, outcome.phones.size), Tone.NEUTRAL, Modifier.testTag("notifications-test-result"), icon = null)
            } else {
                Banner(stringResource(Res.string.notifications_test_undelivered), Tone.WARN, Modifier.testTag("notifications-test-result"))
            }
            if (outcome.phones.isNotEmpty()) Group(Modifier.testTag("notifications-test-phones")) {
                outcome.phones.forEachIndexed { index, phone ->
                    if (index > 0) GroupDivider()
                    GroupRow {
                        Text(phone.words(index + 1), color = colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f).testTag("notifications-test-phone-$index"))
                    }
                }
            }
        }
        PushTestOutcome.Off -> Banner(stringResource(Res.string.notifications_test_off), Tone.WARN, Modifier.testTag("notifications-test-result"))
        PushTestOutcome.NoPhone -> Banner(stringResource(Res.string.notifications_test_no_phone), Tone.WARN, Modifier.testTag("notifications-test-result"))
    }
}

/** "Phone 2: refused (bad_token)": the server's code only when it is one. */
@Composable
private fun PushTestPhone.words(number: Int): String {
    val status = stringResource(status.label())
    return visibleCode(error)?.let { stringResource(Res.string.notifications_test_phone_code, number, status, it) }
        ?: stringResource(Res.string.notifications_test_phone, number, status)
}

private fun PushTestStatus.label(): StringResource = when (this) {
    PushTestStatus.SENT -> Res.string.push_test_status_sent
    PushTestStatus.UNREGISTERED -> Res.string.push_test_status_unregistered
    PushTestStatus.RETRY -> Res.string.push_test_status_retry
    PushTestStatus.REJECTED -> Res.string.push_test_status_rejected
    PushTestStatus.OTHER -> Res.string.push_test_status_other
}

private fun NotificationKind.title(): StringResource = when (this) {
    NotificationKind.WAITING -> Res.string.notifications_kind_waiting
    NotificationKind.TURN_COMPLETED -> Res.string.notifications_kind_turn_completed
    NotificationKind.TURN_FAILED -> Res.string.notifications_kind_turn_failed
    NotificationKind.SESSION_FAILED -> Res.string.notifications_kind_session_failed
}

private fun NotificationKind.hint(): StringResource = when (this) {
    NotificationKind.WAITING -> Res.string.notifications_kind_waiting_hint
    NotificationKind.TURN_COMPLETED -> Res.string.notifications_kind_turn_completed_hint
    NotificationKind.TURN_FAILED -> Res.string.notifications_kind_turn_failed_hint
    NotificationKind.SESSION_FAILED -> Res.string.notifications_kind_session_failed_hint
}

private fun NotificationToggle.label(): StringResource = when (this) {
    NotificationToggle.REQUESTS -> Res.string.notifications_requests
    NotificationToggle.DECISIONS -> Res.string.notifications_decisions
    NotificationToggle.SPENDING -> Res.string.notifications_spending
}
