// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import run.ostrom.app.presentation.components.EnterAction
import run.ostrom.app.presentation.components.enterAction
import run.ostrom.app.presentation.components.isEnter
import run.ostrom.app.presentation.components.isFromHardwareKeyboard
import run.ostrom.app.presentation.components.pointerFirstPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What Enter does in a field that submits, from the key events the desktop delivers. */
@OptIn(InternalComposeUiApi::class)
class KeyActionsTest {
    private fun pressed(key: Key, ctrl: Boolean = false, meta: Boolean = false, alt: Boolean = false, shift: Boolean = false) =
        KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = ctrl, isMetaPressed = meta, isAltPressed = alt, isShiftPressed = shift)

    /** The desktop delivers the typed character as its own event, of no known type. */
    private fun typed(char: Char) = KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = char.code)

    @Test
    fun theDesktopIsPointerFirstAndItsKeysComeFromAKeyboard() {
        assertTrue(pointerFirstPlatform)
        assertTrue(pressed(Key.Enter).isFromHardwareKeyboard())
        assertEquals(EnterAction.SUBMIT, enterAction(pressed(Key.Enter), singleLine = false))
    }

    @Test
    fun enterIsTheEnterKeysAndTheLineFeedsTheDesktopTypes() {
        assertTrue(pressed(Key.Enter).isEnter())
        assertTrue(pressed(Key.NumPadEnter).isEnter())
        assertTrue(typed('\n').isEnter())
        assertTrue(typed('\r').isEnter())
        assertFalse(typed('a').isEnter())
        assertFalse(pressed(Key.A).isEnter())
    }

    @Test
    fun enterSendsAndShiftOrAltEnterBreaksTheLine() {
        val enter = pressed(Key.Enter)
        assertEquals(EnterAction.SUBMIT, enterAction(enter, singleLine = false, hardware = true))
        assertEquals(EnterAction.PASS, enterAction(enter, singleLine = false, hardware = false), "an on-screen keyboard keeps breaking the line")
        assertEquals(EnterAction.PASS, enterAction(pressed(Key.A), singleLine = false, hardware = true))
        assertEquals(EnterAction.SUBMIT, enterAction(pressed(Key.Enter, ctrl = true), singleLine = false))
        assertEquals(EnterAction.SUBMIT, enterAction(pressed(Key.Enter, meta = true), singleLine = true))
        assertEquals(EnterAction.NEWLINE, enterAction(pressed(Key.Enter, shift = true), singleLine = false))
        assertEquals(EnterAction.NEWLINE, enterAction(pressed(Key.Enter, alt = true), singleLine = false))
        assertEquals(EnterAction.PASS, enterAction(pressed(Key.Enter, shift = true), singleLine = true), "a single line has none to break")
        assertEquals(EnterAction.PASS, enterAction(pressed(Key.Enter, alt = true), singleLine = true))
    }
}
