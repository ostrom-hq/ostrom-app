// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/components/SessionComponents.kt (ConfirmingLinks); see NOTICE.
package run.ostrom.app.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.jetbrains.compose.resources.stringResource
import run.ostrom.app.domain.usecases.artifacts.isWebLink
import run.ostrom.app.presentation.theme.MonospaceStyle
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.theme.Radii
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.action_cancel
import run.ostrom.app.resources.link_open_confirm
import run.ostrom.app.resources.link_open_hint
import run.ostrom.app.resources.link_open_refused
import run.ostrom.app.resources.link_open_title

/**
 * Links opened from [content] (a published page) go to the browser only after their address is shown and confirmed;
 * anything but an `http` or `https` link is refused.
 */
@Composable
fun ConfirmingLinks(content: @Composable () -> Unit) {
    val system = LocalUriHandler.current
    var pending by remember { mutableStateOf<String?>(null) }
    val handler = remember(system) { object : UriHandler { override fun openUri(uri: String) { pending = uri } } }
    CompositionLocalProvider(LocalUriHandler provides handler) { content() }
    pending?.let { uri ->
        val allowed = isWebLink(uri)
        Dialog(onDismissRequest = { pending = null }) {
            val colors = Ostrom.colors
            Column(
                Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(Radii.card).background(colors.panel).border(1.dp, colors.line, Radii.card).padding(18.dp).testTag("link-confirm"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(Res.string.link_open_title), color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (allowed) uri else stringResource(Res.string.link_open_refused),
                    style = MonospaceStyle,
                    color = colors.text,
                    modifier = Modifier.fillMaxWidth().clip(Radii.control).background(colors.panelAlt).border(1.dp, colors.line, Radii.control).padding(10.dp),
                )
                if (allowed) Text(stringResource(Res.string.link_open_hint), color = colors.muted, fontSize = 12.5.sp, lineHeight = 17.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton(stringResource(Res.string.action_cancel), onClick = { pending = null }, kind = ButtonKind.SECONDARY, modifier = Modifier.weight(1f))
                    if (allowed) AppButton(stringResource(Res.string.link_open_confirm), onClick = { pending = null; system.openUri(uri) }, modifier = Modifier.weight(1f).testTag("link-open"))
                }
            }
        }
    }
}
