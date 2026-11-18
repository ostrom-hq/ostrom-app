// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonMain/kotlin/me/nathanfallet/teamcode/companion/presentation/components/Adaptive.kt; see NOTICE.
package run.ostrom.app.presentation.components

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint

/** What Enter does in a text field that submits ([enterAction]). */
enum class EnterAction { SUBMIT, NEWLINE, PASS }

/**
 * Enter sends, Shift+Enter and Alt+Enter break the line (Ctrl+Enter and Cmd+Enter send too), only from a physical
 * keyboard: an on-screen keyboard's Enter keeps breaking the line. A single-line field has no line to break.
 */
fun enterAction(event: KeyEvent, singleLine: Boolean, hardware: Boolean = event.isFromHardwareKeyboard()): EnterAction {
    if (!hardware || !event.isEnter()) return EnterAction.PASS
    return when {
        event.isCtrlPressed || event.isMetaPressed -> EnterAction.SUBMIT
        event.isShiftPressed || event.isAltPressed -> if (singleLine) EnterAction.PASS else EnterAction.NEWLINE
        else -> EnterAction.SUBMIT
    }
}

/** The Enter key, or the line feed it types (the desktop delivers the typed character as its own event). */
internal fun KeyEvent.isEnter(): Boolean =
    key == Key.Enter || key == Key.NumPadEnter || (type == KeyEventType.Unknown && (utf16CodePoint == '\n'.code || utf16CodePoint == '\r'.code))
