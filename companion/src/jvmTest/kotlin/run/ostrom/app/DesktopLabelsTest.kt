// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app

import run.ostrom.app.presentation.DesktopLabel
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopLabelsTest {
    @Test
    fun theDesktopShellHasItsSixLabels() {
        assertEquals(
            listOf("TRAY_OPEN", "TRAY_QUIT", "MENU_FILE", "MENU_ADD_ACCOUNT", "MENU_CLOSE_WINDOW", "MENU_VIEW"),
            DesktopLabel.entries.map { it.name },
        )
        assertEquals(DesktopLabel.MENU_VIEW, DesktopLabel.valueOf("MENU_VIEW"))
    }
}
