// SPDX-License-Identifier: AGPL-3.0-only
// Ported from TeamCode (Apache-2.0), companion/src/iosMain/kotlin/me/nathanfallet/teamcode/companion/presentation/components/Adaptive.ios.kt; see NOTICE.
package run.ostrom.app.presentation.components

import androidx.compose.ui.input.key.KeyEvent

actual val pointerFirstPlatform: Boolean = false

/** UIKit types the on-screen keyboard's text through the text input session: key events come from a keyboard. */
actual fun KeyEvent.isFromHardwareKeyboard(): Boolean = true
