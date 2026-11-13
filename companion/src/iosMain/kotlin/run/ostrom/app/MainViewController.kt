// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/iosMain/.../MainViewController.kt; see NOTICE.
package run.ostrom.app

import androidx.compose.ui.window.ComposeUIViewController
import run.ostrom.app.di.initKoin
import run.ostrom.app.di.platformModule

private var koinStarted = false

private fun startKoinOnce() {
    if (!koinStarted) {
        koinStarted = true
        initKoin(platformModule())
    }
}

@Suppress("FunctionName", "unused") // Called from Swift (ContentView.swift).
fun MainViewController() = ComposeUIViewController(configure = { startKoinOnce() }) { App() }
