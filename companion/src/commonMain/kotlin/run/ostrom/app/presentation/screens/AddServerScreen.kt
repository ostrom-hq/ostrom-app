// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/screens/AddServerScreen.kt; see NOTICE.
package run.ostrom.app.presentation.screens

import run.ostrom.app.presentation.viewmodels.words
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Check
import run.ostrom.app.presentation.components.BackLink
import run.ostrom.app.presentation.components.Banner
import run.ostrom.app.presentation.components.BrandGlyph
import run.ostrom.app.presentation.components.ButtonKind
import run.ostrom.app.presentation.components.FormPage
import run.ostrom.app.presentation.components.IconTile
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.AppTextField
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.theme.MonospaceStyle
import run.ostrom.app.presentation.theme.Radii
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.viewmodels.AddServerViewModel
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.action_back
import run.ostrom.app.resources.action_continue
import run.ostrom.app.resources.add_server_check
import run.ostrom.app.resources.add_server_found
import run.ostrom.app.resources.add_server_intro
import run.ostrom.app.resources.add_server_new_warning
import run.ostrom.app.resources.add_server_origin
import run.ostrom.app.resources.add_server_title
import run.ostrom.app.resources.app_name
import run.ostrom.app.resources.demo_try
import run.ostrom.app.resources.demo_try_hint
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AddServerScreen(
    canGoBack: Boolean,
    onBack: () -> Unit,
    onContinue: (origin: String) -> Unit,
    viewModel: AddServerViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val server = state.server
    FormPage(footer = {
        if (server == null) {
            AppButton(stringResource(Res.string.add_server_check), onClick = viewModel::check, enabled = state.origin.isNotBlank(), busy = state.checking, modifier = Modifier.testTag("check"))
        } else {
            AppButton(stringResource(Res.string.action_continue), onClick = { onContinue(server.origin) }, modifier = Modifier.testTag("continue"))
        }
        // Only before any account: the demo runs alone, never beside a real account.
        if (state.demoOffered) {
            Text(stringResource(Res.string.demo_try_hint), color = Ostrom.colors.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            AppButton(stringResource(Res.string.demo_try), onClick = viewModel::tryDemo, kind = ButtonKind.SECONDARY, modifier = Modifier.testTag("try-demo"))
        }
    }) {
        if (canGoBack) BackLink(stringResource(Res.string.action_back), onBack, Modifier.padding(start = 0.dp))
        Row(Modifier.padding(top = if (canGoBack) 4.dp else 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BrandGlyph(size = 28.dp)
            Text(stringResource(Res.string.app_name), color = Ostrom.colors.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        PageHeading(stringResource(Res.string.add_server_title), stringResource(Res.string.add_server_intro))
        AppTextField(
            value = state.origin,
            onValueChange = viewModel::onOriginChange,
            label = stringResource(Res.string.add_server_origin),
            placeholder = "https://ostrom.example.org",
            mono = true,
            error = state.error != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { viewModel.check() }),
            onSubmit = viewModel::check,
            tag = "origin",
        )
        state.error?.let { Banner(it.words(), Tone.DANGER, Modifier.testTag("error")) }
        if (server != null) {
            ServerCard(PhosphorIcons.Regular.Check, stringResource(Res.string.add_server_found), server.origin, Modifier.testTag("server"))
            if (state.newServer) Banner(stringResource(Res.string.add_server_new_warning), Tone.WARN)
        }
    }
}

/** A sign-in page's title and its line of explanation. */
@Composable
internal fun PageHeading(title: String, intro: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Ostrom.colors.text, modifier = Modifier.semantics { heading() })
        if (intro != null) Text(intro, color = Ostrom.colors.muted, fontSize = 15.sp, lineHeight = 21.sp)
    }
}

/** The server (or account) a page acts on, its origin in full. [title] is muted when it only introduces the origin. */
@Composable
internal fun ServerCard(icon: ImageVector, title: String, origin: String, modifier: Modifier = Modifier, mutedTitle: Boolean = false) {
    val colors = Ostrom.colors
    Row(
        modifier.fillMaxWidth().clip(Radii.card).background(colors.panel).border(1.dp, colors.line, Radii.card).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(icon, tint = colors.accentText, size = 36.dp)
        Column {
            if (mutedTitle) Text(title, color = colors.muted, fontSize = 12.5.sp) else Text(title, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(origin, style = MonospaceStyle, color = if (mutedTitle) colors.text else colors.muted)
        }
    }
}
