// SPDX-License-Identifier: AGPL-3.0-only
// Ported from TeamCode (Apache-2.0), companion/src/androidMain/kotlin/me/nathanfallet/teamcode/companion/presentation/components/Adaptive.android.kt; see NOTICE.
package run.ostrom.app.presentation.components

import android.view.KeyCharacterMap
import androidx.compose.ui.input.key.KeyEvent

actual val pointerFirstPlatform: Boolean = false

/** Soft keyboards send their keys from the virtual keyboard device, or flag them as soft. */
actual fun KeyEvent.isFromHardwareKeyboard(): Boolean =
    nativeKeyEvent.deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD && (nativeKeyEvent.flags and android.view.KeyEvent.FLAG_SOFT_KEYBOARD) == 0
