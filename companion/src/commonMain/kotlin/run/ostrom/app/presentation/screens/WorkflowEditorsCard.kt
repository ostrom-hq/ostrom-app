// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/screens/WorkflowEditorsCard.kt; see NOTICE.
package run.ostrom.app.presentation.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowSquareOut
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource
import run.ostrom.app.domain.models.WorkflowEditRights
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.ButtonKind
import run.ostrom.app.presentation.theme.MetaStyle
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.wfe_editors_admins
import run.ostrom.app.resources.wfe_editors_all
import run.ostrom.app.resources.wfe_editors_hint
import run.ostrom.app.resources.wfe_editors_members
import run.ostrom.app.resources.wfe_editors_open
import run.ostrom.app.resources.wfe_editors_title

/**
 * "Who can edit workflows", read only, for an owner or an admin as in TeamCode: the rule changes from a console session
 * only (the server refuses it to a token), so the card offers the console's workflows page ([consoleUrl]).
 */
@Composable
fun WorkflowEditorsCard(rights: WorkflowEditRights, consoleUrl: String?) {
    val colors = Ostrom.colors
    val browser = LocalUriHandler.current
    FoldSection("editors", stringResource(Res.string.wfe_editors_title), initiallyOpen = false) {
        Text(
            when (rights.mode) {
                "all" -> stringResource(Res.string.wfe_editors_all)
                "members" -> stringResource(Res.string.wfe_editors_members, rights.memberCount)
                else -> stringResource(Res.string.wfe_editors_admins)
            },
            color = colors.text, fontSize = 14.sp, modifier = Modifier.testTag("wfe-editors-rule"),
        )
        Text(stringResource(Res.string.wfe_editors_hint), style = MetaStyle, color = colors.muted)
        if (consoleUrl != null) {
            AppButton(stringResource(Res.string.wfe_editors_open), icon = PhosphorIcons.Regular.ArrowSquareOut, kind = ButtonKind.SECONDARY, small = true, fill = false,
                modifier = Modifier.testTag("wfe-editors-open"), onClick = {
                    try {
                        browser.openUri(consoleUrl)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // No browser: the console is reached by hand; nothing else to offer here.
                    }
                })
        }
    }
}
