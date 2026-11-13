// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.app.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import run.ostrom.app.domain.models.Account
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.ButtonKind
import run.ostrom.app.presentation.components.FormPage
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.viewmodels.Incompatible
import run.ostrom.app.presentation.viewmodels.SettingsViewModel
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.incompatible_sign_out
import run.ostrom.app.resources.incompatible_title
import run.ostrom.app.resources.incompatible_too_old
import run.ostrom.app.resources.incompatible_unsupported

/** Blocks the app when the server speaks another API version than this app: only signing out leaves it. */
@Composable
fun IncompatibleServerScreen(incompatible: Incompatible, account: Account?, viewModel: SettingsViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = Ostrom.colors
    FormPage(footer = {
        if (account != null) AppButton(stringResource(Res.string.incompatible_sign_out), onClick = { viewModel.signOut(account) }, kind = ButtonKind.DANGER, busy = state.working,
            modifier = Modifier.testTag("incompatible-sign-out"))
    }) {
        Column(Modifier.fillMaxSize().padding(top = 48.dp).widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.Start) {
            Text(stringResource(Res.string.incompatible_title), style = MaterialTheme.typography.headlineMedium, color = colors.text, modifier = Modifier.semantics { heading() })
            val message = incompatible.apiVersion?.let { stringResource(Res.string.incompatible_unsupported, it, incompatible.supported) }
                ?: stringResource(Res.string.incompatible_too_old, incompatible.supported)
            Text(message, color = colors.muted, fontSize = 16.sp, lineHeight = 22.sp, modifier = Modifier.testTag("incompatible-message"))
            account?.let { Text(it.origin, color = colors.muted, fontSize = 13.sp) }
        }
    }
}
