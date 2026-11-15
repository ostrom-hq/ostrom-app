// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.domain.repositories

import kotlinx.coroutines.flow.StateFlow
import run.ostrom.app.domain.models.PermissionMode
import run.ostrom.app.domain.models.ThemePreference

interface PreferencesRepository {
    val theme: StateFlow<ThemePreference>
    fun setTheme(theme: ThemePreference)

    /** The permission mode chosen at the last launch of a session on a device; [PermissionMode.ASK] until one was chosen. */
    val permissionMode: PermissionMode
    fun setPermissionMode(mode: PermissionMode)
}
