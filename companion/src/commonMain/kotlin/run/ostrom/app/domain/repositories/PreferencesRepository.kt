// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.app.domain.repositories

import kotlinx.coroutines.flow.StateFlow
import run.ostrom.app.domain.models.ThemePreference

interface PreferencesRepository {
    val theme: StateFlow<ThemePreference>
    fun setTheme(theme: ThemePreference)
}
