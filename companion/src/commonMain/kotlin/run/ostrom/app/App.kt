// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../App.kt; see NOTICE.
package run.ostrom.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import run.ostrom.app.presentation.components.LocalDense
import run.ostrom.app.presentation.components.LocalWideLayout
import run.ostrom.app.presentation.components.isWideWindow
import run.ostrom.app.presentation.components.pointerFirstPlatform
import run.ostrom.app.presentation.navigation.OstromNavHost
import run.ostrom.app.presentation.theme.OstromTheme
import run.ostrom.app.presentation.viewmodels.RootViewModel

/** The app, the same on every platform; each entry point starts Koin with its platform module first. */
@Composable
fun App() {
    val root: RootViewModel = koinViewModel()
    val theme by root.theme.collectAsStateWithLifecycle()
    OstromTheme(theme) {
        CompositionLocalProvider(LocalWideLayout provides isWideWindow(), LocalDense provides pointerFirstPlatform) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                OstromNavHost(root)
            }
        }
    }
}
