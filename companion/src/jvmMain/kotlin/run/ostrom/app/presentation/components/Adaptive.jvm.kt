// SPDX-License-Identifier: AGPL-3.0-only
// Ported from TeamCode (Apache-2.0), companion/src/jvmMain/kotlin/me/nathanfallet/teamcode/companion/presentation/components/Adaptive.jvm.kt; see NOTICE.
package run.ostrom.app.presentation.components

import androidx.compose.ui.input.key.KeyEvent

actual val pointerFirstPlatform: Boolean = true

/** The desktop has no on-screen keyboard of its own: every key event comes from a keyboard. */
actual fun KeyEvent.isFromHardwareKeyboard(): Boolean = true
