// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation.components

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * An amount in US dollars as agents' providers bill it, with two decimals, in the person's [language]: `$1,204.50` in
 * English, `1 204,50 $` in French (narrow no-break spaces, as the French typography wants).
 */
fun formatUsd(usd: Double, language: String): String {
    if (!usd.isFinite()) return "—"
    val cents = (abs(usd) * 100).roundToLong()
    val whole = (cents / 100).toString()
    val fraction = (cents % 100).toString().padStart(2, '0')
    val french = language.lowercase().startsWith("fr")
    val group = if (french) " " else ","
    val grouped = whole.reversed().chunked(3).joinToString(group).reversed()
    val sign = if (usd < 0 && cents > 0) "-" else ""
    return if (french) "$sign$grouped,$fraction $" else "$sign$$grouped.$fraction"
}
