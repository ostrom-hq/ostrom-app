// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/components/UpdateBanner.kt; see NOTICE.
package run.ostrom.app.presentation.components

import run.ostrom.app.domain.services.CliUpdateException
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.update_failed_failed
import run.ostrom.app.resources.update_failed_not_installed
import run.ostrom.app.resources.update_failed_still_running
import run.ostrom.app.resources.update_failed_unexpected

internal fun CliUpdateException.Reason.sentence() = when (this) {
    CliUpdateException.Reason.NOT_INSTALLED -> Res.string.update_failed_not_installed
    CliUpdateException.Reason.STILL_RUNNING -> Res.string.update_failed_still_running
    CliUpdateException.Reason.FAILED -> Res.string.update_failed_failed
    CliUpdateException.Reason.UNEXPECTED_OUTPUT -> Res.string.update_failed_unexpected
}
