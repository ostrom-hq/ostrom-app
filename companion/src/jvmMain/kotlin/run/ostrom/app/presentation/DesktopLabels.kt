// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/jvmMain/.../presentation/DesktopLabels.kt; see NOTICE.
package run.ostrom.app.presentation

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import run.ostrom.app.presentation.screens.MainTab
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.menu_add_account
import run.ostrom.app.resources.menu_close_window
import run.ostrom.app.resources.menu_file
import run.ostrom.app.resources.menu_view
import run.ostrom.app.resources.tray_open
import run.ostrom.app.resources.tray_quit

/** The texts of the desktop shell (menu bar, menu bar icon), which lives in `:companion-desktop` while the resources stay here. */
enum class DesktopLabel { TRAY_OPEN, TRAY_QUIT, MENU_FILE, MENU_ADD_ACCOUNT, MENU_CLOSE_WINDOW, MENU_VIEW }

/** The text of [label] in the language of the system. */
@Composable
fun desktopLabel(label: DesktopLabel): String = stringResource(
    when (label) {
        DesktopLabel.TRAY_OPEN -> Res.string.tray_open
        DesktopLabel.TRAY_QUIT -> Res.string.tray_quit
        DesktopLabel.MENU_FILE -> Res.string.menu_file
        DesktopLabel.MENU_ADD_ACCOUNT -> Res.string.menu_add_account
        DesktopLabel.MENU_CLOSE_WINDOW -> Res.string.menu_close_window
        DesktopLabel.MENU_VIEW -> Res.string.menu_view
    },
)

/** The name of [tab], as its tab shows it, for the menu bar's View menu. */
@Composable
fun tabLabel(tab: MainTab): String = stringResource(tab.label)
