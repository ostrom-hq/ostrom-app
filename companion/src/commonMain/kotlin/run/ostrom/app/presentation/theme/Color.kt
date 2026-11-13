// SPDX-License-Identifier: AGPL-3.0-only
// Ported from TeamCode (Apache-2.0), companion/src/commonMain/kotlin/me/nathanfallet/teamcode/companion/presentation/theme/Color.kt; see NOTICE.
package run.ostrom.app.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The app's color tokens. Ostrom's "Signal" brand tokens (the console's and the former app's
 * palette): garnet means "a person is awaited or has just decided", amber is budget, red is error, on warm ink greys.
 * [warnBackground] and [dangerBackground] tint the banners and chips of those states.
 */
@Immutable
data class OstromColors(
    val background: Color,
    val panel: Color,
    val panelAlt: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val accentText: Color,
    val accentDim: Color,
    val onAccent: Color,
    val chip: Color,
    val chipText: Color,
    val warn: Color,
    val warnBackground: Color,
    val danger: Color,
    val dangerBackground: Color,
    val bubble: Color,
    val onBubble: Color,
    val shade: Color,
    val code: Color,
    val skeleton: Color,
    /** Under the pointer: a row or a card the mouse is over. */
    val hover: Color,
    /** The desktop and tablet sidebar. */
    val sidebar: Color,
    val isDark: Boolean,
)

val DarkPalette = OstromColors(
    background = Color(0xFF0F0A0C), // ink950
    panel = Color(0xFF1A1214), // ink900
    panelAlt = Color(0xFF140E10), // inkNight
    line = Color(0xFF35292D), // ink800
    text = Color(0xFFF7F5F5), // ink50
    muted = Color(0xFFA3979A), // ink400
    accent = Color(0xFFD6455E), // garnet450
    accentText = Color(0xFFF390A9), // garnet300
    accentDim = Color(0xFF5C1025), // garnet700
    onAccent = Color(0xFFFFFFFF),
    chip = Color(0xFF1E1619), // ink850
    chipText = Color(0xFFE2BFC8), // garnet200
    warn = Color(0xFFE3A33B), // amber400
    warnBackground = Color(0xFF33240C), // amber900
    danger = Color(0xFFFF6B5A), // red400
    dangerBackground = Color(0xFF3A1714), // red900
    bubble = Color(0xFF4A1D26), // garnet900
    onBubble = Color(0xFFF8EEF1), // garnet50
    shade = Color(0x99000000),
    code = Color(0xFF0F0A0C),
    skeleton = Color(0xFF2A2024),
    hover = Color(0xFF241A1D),
    sidebar = Color(0xFF140E10),
    isDark = true,
)

val LightPalette = OstromColors(
    background = Color(0xFFFBFAFA), // ink25
    panel = Color.White,
    panelAlt = Color(0xFFF7F5F5), // ink50
    line = Color(0xFFE5DCDE), // ink200
    text = Color(0xFF1A1214), // ink900
    muted = Color(0xFF665A5D), // ink600
    accent = Color(0xFF8E1B3A), // garnet500
    accentText = Color(0xFF8E1B3A),
    accentDim = Color(0xFFE2BFC8), // garnet200
    onAccent = Color.White,
    chip = Color(0xFFEEE9EA), // ink100
    chipText = Color(0xFF74152F), // garnet600
    warn = Color(0xFF8A5A12), // amber800
    warnBackground = Color(0xFFFBF1DF), // amber50
    danger = Color(0xFFC8352A), // red600
    dangerBackground = Color(0xFFFBE6E3), // red50
    bubble = Color(0xFFF8EEF1), // garnet50
    onBubble = Color(0xFF4A1D26),
    shade = Color(0x591A1214),
    code = Color(0xFFF7F5F5),
    skeleton = Color(0xFFEEE9EA),
    hover = Color(0xFFF2EDEE),
    sidebar = Color(0xFFF7F5F5),
    isDark = false,
)

val LocalOstromColors = staticCompositionLocalOf { DarkPalette }

/** The Material scheme built from the tokens, for the Material components the app still uses (menus, sheets, dialogs). */
fun OstromColors.materialScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = bubble,
        onPrimaryContainer = onBubble,
        secondary = muted,
        onSecondary = background,
        secondaryContainer = chip,
        onSecondaryContainer = text,
        tertiary = warn,
        onTertiary = background,
        background = background,
        onBackground = text,
        surface = panel,
        onSurface = text,
        surfaceVariant = panelAlt,
        onSurfaceVariant = muted,
        surfaceContainerLowest = background,
        surfaceContainerLow = panelAlt,
        surfaceContainer = panel,
        surfaceContainerHigh = panel,
        surfaceContainerHighest = chip,
        surfaceTint = Color.Transparent,
        outline = line,
        outlineVariant = line,
        error = danger,
        onError = background,
        errorContainer = dangerBackground,
        onErrorContainer = danger,
        scrim = shade,
    )
}
