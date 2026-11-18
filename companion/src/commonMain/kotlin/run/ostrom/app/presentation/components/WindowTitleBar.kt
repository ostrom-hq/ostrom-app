// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonMain/kotlin/me/nathanfallet/teamcode/companion/presentation/components/Adaptive.kt; see NOTICE.
package run.ostrom.app.presentation.components

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The window's title bar when the app draws under it (the desktop on macOS, [WindowTitleBar.MAC]): its [height], and the
 * [start] the window's own buttons take at its left. The sidebar puts its header beside those buttons, and a screen without
 * the sidebar starts below the bar. None elsewhere, and in fullscreen, where macOS hides the bar.
 */
@Immutable
data class WindowTitleBar(val height: Dp = 0.dp, val start: Dp = 0.dp) {
    companion object {
        /**
         * macOS's title bar with the full-size content view: 28 points tall, its three buttons from 9 to 63 points; the
         * header starts one button's gap after them.
         */
        val MAC = WindowTitleBar(height = 28.dp, start = 76.dp)

        /**
         * macOS 26 and later seat the buttons lower (about 20 points from the top) and bigger: the bar is taller so the
         * header, centered in it, sits on their line instead of above it; the header starts after the wider group.
         */
        val MAC_TAHOE = WindowTitleBar(height = 40.dp, start = 84.dp)

        /** The bar of the macOS at [osVersion] (`os.version`: "15.6.1", "26.0"); the older one when unknown. */
        fun forMac(osVersion: String?): WindowTitleBar =
            if ((osVersion?.let { it.substringBefore('.').trim().toIntOrNull() } ?: 0) >= 26) MAC_TAHOE else MAC
    }
}
