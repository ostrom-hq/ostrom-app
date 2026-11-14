// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/components/UpdateBanner.kt; see NOTICE.
package run.ostrom.app.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowsClockwise
import com.adamglin.phosphoricons.regular.CheckCircle
import com.adamglin.phosphoricons.regular.WarningCircle
import com.adamglin.phosphoricons.regular.X
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.mp.KoinPlatform
import run.ostrom.app.domain.services.CliUpdateException
import run.ostrom.app.domain.services.CliUpdater
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.viewmodels.UpdateBannerState
import run.ostrom.app.presentation.viewmodels.UpdateBannerViewModel
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.action_close
import run.ostrom.app.resources.update_action
import run.ostrom.app.resources.update_available_detail
import run.ostrom.app.resources.update_available_title
import run.ostrom.app.resources.update_failed_failed
import run.ostrom.app.resources.update_failed_not_installed
import run.ostrom.app.resources.update_failed_still_running
import run.ostrom.app.resources.update_failed_title
import run.ostrom.app.resources.update_failed_unexpected
import run.ostrom.app.resources.update_not_now
import run.ostrom.app.resources.update_try_again
import run.ostrom.app.resources.update_updated_detail
import run.ostrom.app.resources.update_updated_title
import run.ostrom.app.resources.update_updating_title

/**
 * "ostrom X is available" at the top of the signed-in app's content: one click runs `ostrom update`, its lines shown
 * as they come, then says the CLI was updated. Only where the desktop's module binds a [CliUpdater]: the phones bind
 * none, so nothing shows there.
 */
@Composable
fun UpdateBanner() {
    // The Koin started for this run: a binding the desktop's module alone provides.
    val available = remember { KoinPlatform.getKoinOrNull()?.getOrNull<CliUpdater>() != null }
    if (!available) return
    val viewModel: UpdateBannerViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    UpdateBannerContent(state, onUpdate = viewModel::update, onDismiss = viewModel::dismiss)
}

@Composable
fun UpdateBannerContent(state: UpdateBannerState, onUpdate: () -> Unit, onDismiss: () -> Unit) {
    if (state == UpdateBannerState.Hidden) return
    val colors = Ostrom.colors
    val failed = state is UpdateBannerState.Failed
    Column(Modifier.fillMaxWidth().background(if (failed) colors.dangerBackground else colors.panel).testTag("update-banner")) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = if (failed) colors.danger else colors.accentText
            when (state) {
                is UpdateBannerState.Updating -> CircularProgressIndicator(Modifier.size(16.dp), color = tint, strokeWidth = 2.dp)
                else -> Icon(
                    when (state) {
                        is UpdateBannerState.Failed -> PhosphorIcons.Regular.WarningCircle
                        is UpdateBannerState.Updated -> PhosphorIcons.Regular.CheckCircle
                        else -> PhosphorIcons.Regular.ArrowsClockwise
                    },
                    contentDescription = null, tint = tint, modifier = Modifier.size(18.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val (title, detail) = when (state) {
                    is UpdateBannerState.Available -> stringResource(Res.string.update_available_title, state.version) to
                        stringResource(Res.string.update_available_detail, state.installed)
                    is UpdateBannerState.Updating -> stringResource(Res.string.update_updating_title, state.version) to state.output.lastOrNull()
                    is UpdateBannerState.Updated -> stringResource(Res.string.update_updated_title, state.version) to stringResource(Res.string.update_updated_detail)
                    is UpdateBannerState.Failed -> stringResource(Res.string.update_failed_title, state.version) to
                        (listOf(stringResource(state.reason.sentence())) + state.lines.takeLast(2)).joinToString("\n")
                    UpdateBannerState.Hidden -> return@Row
                }
                Text(title, color = if (failed) colors.danger else colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                detail?.let {
                    val output = state is UpdateBannerState.Updating
                    Text(
                        it, color = if (failed) colors.danger else colors.muted, fontSize = if (output) 12.sp else 12.5.sp, lineHeight = 17.sp,
                        fontFamily = if (output) FontFamily.Monospace else null, maxLines = if (failed) 4 else 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("update-banner-detail"),
                    )
                }
            }
            when (state) {
                is UpdateBannerState.Available -> {
                    AppButton(stringResource(Res.string.update_action), onUpdate, small = true, fill = false, modifier = Modifier.testTag("update-banner-update"))
                    AppIconButton(PhosphorIcons.Regular.X, stringResource(Res.string.update_not_now), onDismiss, modifier = Modifier.testTag("update-banner-dismiss"))
                }
                is UpdateBannerState.Failed -> {
                    if (state.reason != CliUpdateException.Reason.STILL_RUNNING) AppButton(stringResource(Res.string.update_try_again), onUpdate, small = true, fill = false, kind = ButtonKind.SECONDARY, modifier = Modifier.testTag("update-banner-retry"))
                    AppIconButton(PhosphorIcons.Regular.X, stringResource(Res.string.action_close), onDismiss, modifier = Modifier.testTag("update-banner-dismiss"))
                }
                is UpdateBannerState.Updated ->
                    AppIconButton(PhosphorIcons.Regular.X, stringResource(Res.string.action_close), onDismiss, modifier = Modifier.testTag("update-banner-dismiss"))
                else -> Unit
            }
        }
        HorizontalDivider(color = colors.line)
    }
}

private fun CliUpdateException.Reason.sentence() = when (this) {
    CliUpdateException.Reason.NOT_INSTALLED -> Res.string.update_failed_not_installed
    CliUpdateException.Reason.STILL_RUNNING -> Res.string.update_failed_still_running
    CliUpdateException.Reason.FAILED -> Res.string.update_failed_failed
    CliUpdateException.Reason.UNEXPECTED_OUTPUT -> Res.string.update_failed_unexpected
}
