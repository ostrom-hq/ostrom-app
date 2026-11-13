// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.app.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import run.ostrom.app.App
import run.ostrom.app.di.initKoin
import run.ostrom.app.di.platformModule

fun main() {
    initKoin(platformModule())
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Ostrom",
            state = rememberWindowState(size = DpSize(1100.dp, 760.dp)),
        ) {
            window.minimumSize = java.awt.Dimension(360, 560)
            App()
        }
    }
}
