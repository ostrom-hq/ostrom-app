// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/iosMain/.../MainViewController.kt; see NOTICE.
package run.ostrom.app

import androidx.compose.ui.window.ComposeUIViewController
import org.koin.dsl.module
import run.ostrom.app.di.initKoin
import run.ostrom.app.di.platformModule
import run.ostrom.app.di.startPush
import run.ostrom.app.domain.services.PushPlatform
import run.ostrom.app.infrastructure.push.IosPushKeys
import run.ostrom.app.infrastructure.push.IosPushPlatform
import run.ostrom.app.presentation.navigation.IncomingLinks

private var koinStarted = false
private var push: IosPushPlatform? = null

private fun startKoinOnce() {
    if (!koinStarted) {
        koinStarted = true
        val platform = push
        val koin = initKoin(platformModule()) { platform?.let { modules(module { single<PushPlatform> { platform } }) } }.koin
        if (platform != null) startPush(koin)
    }
}

@Suppress("FunctionName", "unused") // Called from Swift (ContentView.swift).
fun MainViewController() = ComposeUIViewController(configure = { startKoinOnce() }) { App() }

/** A link the system handed to the app: a tapped notification (AppDelegate.swift). */
@Suppress("unused") // Called from Swift.
fun openLink(link: String) = IncomingLinks.open(link)

/** Push is on in this build (AppDelegate.swift, with GoogleService-Info.plist): called before the first screen. */
@Suppress("unused") // Called from Swift.
fun enablePush(keys: IosPushKeys) {
    if (push == null) push = IosPushPlatform(keys)
}

/** FCM gave or rotated the installation's token (AppDelegate.swift's MessagingDelegate). */
@Suppress("unused") // Called from Swift.
fun pushTokenChanged(token: String?) {
    push?.updateToken(token)
}
