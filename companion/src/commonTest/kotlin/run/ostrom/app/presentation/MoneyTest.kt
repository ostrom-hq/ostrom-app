// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.app.presentation

import run.ostrom.app.presentation.components.formatUsd
import kotlin.test.Test
import kotlin.test.assertEquals

class MoneyTest {
    @Test
    fun dollarsAreWrittenTheWayEachLanguageWritesThem() {
        assertEquals("$1,204.50", formatUsd(1204.5, "en"))
        assertEquals("$0.00", formatUsd(0.0, "en-US"))
        assertEquals("$1,000,000.01", formatUsd(1_000_000.006, "en"))
        assertEquals("-$3.20", formatUsd(-3.2, "en"))
        assertEquals("1 204,50 $", formatUsd(1204.5, "fr"))
        assertEquals("12,00 $", formatUsd(12.0, "fr-FR"))
    }
}
