// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonMain/kotlin/me/nathanfallet/teamcode/companion/presentation/components/Adaptive.kt; see NOTICE.
package run.ostrom.app.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.window.core.layout.WindowSizeClass
import run.ostrom.app.presentation.theme.Ostrom

/**
 * The window is at least medium-wide (600 dp, Material's breakpoint) and medium-tall (480 dp): a sidebar and a content
 * panel instead of tabs and pushed screens. A phone in landscape is wide but short, and keeps its tabs. Read from
 * Compose's window size class, the same on the desktop, iPad and Android tablets.
 */
@Composable
fun isWideWindow(): Boolean = currentWindowAdaptiveInfoV2().windowSizeClass.let {
    it.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) && it.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
}

/** Provided by the app: true when the window shows the sidebar layout ([isWideWindow]). Sheets become dialogs then. */
val LocalWideLayout = compositionLocalOf { false }

/** Provided by the desktop: the title bar the app draws under, [WindowTitleBar]. */
val LocalWindowTitleBar = staticCompositionLocalOf { WindowTitleBar() }

/** Room for the window's title bar above a screen that fills the window ([LocalWindowTitleBar]). */
fun Modifier.windowTitleBarPadding(): Modifier = composed { padding(top = LocalWindowTitleBar.current.height) }

/** Provided by the app: a mouse-first platform (the desktop), where controls are denser than touch targets. */
val LocalDense = compositionLocalOf { false }

/** The desktop runs on a mouse and keyboard; phones and tablets are touch-first, even with a trackpad attached. */
expect val pointerFirstPlatform: Boolean

/** A key event from a physical keyboard; false for an on-screen keyboard (Android injects those as key events too). */
expect fun KeyEvent.isFromHardwareKeyboard(): Boolean

/** The hand cursor over something clickable (no effect on touch screens). */
fun Modifier.handCursor(): Modifier = pointerHoverIcon(PointerIcon.Hand)

/** A tinted background while the pointer is over the element, in [shape]. */
fun Modifier.hoverHighlight(shape: Shape, color: Color? = null): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val tint = color ?: Ostrom.colors.hover
    this.hoverable(source).then(if (hovered) Modifier.clip(shape).background(tint, shape) else Modifier)
}

/**
 * Up and Down move the keyboard focus to the row above or below, inside a list (the sidebar, the content panel's
 * cards); Enter or Space then opens the focused row, as `clickable` does for a focused element. Compose moves the focus
 * with Tab and Shift+Tab on its own; the arrows are this modifier's, through Compose's focus search
 * ([FocusDirection.Down] and [FocusDirection.Up], which also scroll a lazy list to the row they reach).
 */
fun Modifier.arrowKeyNavigation(): Modifier = composed {
    val focus = LocalFocusManager.current
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || event.isMetaPressed || event.isCtrlPressed || event.isAltPressed) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionDown -> focus.moveFocus(FocusDirection.Down)
            Key.DirectionUp -> focus.moveFocus(FocusDirection.Up)
            else -> false
        }
    }
}

/**
 * The ring around a focused row or card, in [shape]: where the keyboard is. Put it before the element's `clickable`.
 * A click focuses the element too: no ring then, until a key is pressed on it.
 */
fun Modifier.focusRing(shape: Shape): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    var byPointer by remember { mutableStateOf(false) }
    val color = Ostrom.colors.accent
    this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) byPointer = true
        }
    }.onPreviewKeyEvent { byPointer = false; false }
        .onFocusChanged { focused = it.isFocused; if (!it.isFocused) byPointer = false }
        .then(if (focused && !byPointer) Modifier.border(2.dp, color, shape) else Modifier)
        .semantics { this[FocusRingShown] = focused && !byPointer }
}

/** Whether [focusRing] draws its ring, for tests. */
val FocusRingShown = SemanticsPropertyKey<Boolean>("FocusRingShown")

/**
 * A secondary click (right click, two-finger click) on [content] opens a menu where the pointer is. CMP's
 * `ContextMenuArea` exists on the desktop only and serves text selection; this one runs on every platform that has a
 * pointer and opens Material's [DropdownMenu].
 */
@Composable
fun ContextMenuBox(
    modifier: Modifier = Modifier,
    menu: @Composable ColumnScope.(close: () -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var offset by remember { mutableStateOf(DpOffset.Zero) }
    val density = LocalDensity.current
    Box(
        modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                        val position = event.changes.first().position
                        offset = with(density) { DpOffset(position.x.toDp(), position.y.toDp() - size.height.toDp()) }
                        event.changes.forEach { it.consume() }
                        open = true
                    }
                }
            }
        },
    ) {
        content()
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, offset = offset, containerColor = Ostrom.colors.panel) {
            menu { open = false }
        }
    }
}
