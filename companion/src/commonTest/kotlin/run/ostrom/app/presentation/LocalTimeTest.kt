// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation.screens

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalTimeTest {
    private val original = utcOffsetMinutes

    @BeforeTest fun setUp() { utcOffsetMinutes = { 0 } }

    @AfterTest fun tearDown() { utcOffsetMinutes = original }

    @Test
    fun anInstantIsWrittenWithTheDateAndTheClockOfTheZone() {
        assertEquals("2026-10-28 21:00", formatLocal(1_793_221_200_000))
        assertEquals("2026-01-15 00:05", formatLocal(1_768_435_500_000))
        assertEquals("2028-02-29 23:59", formatLocal(1_835_481_540_000), "a leap day")
        assertEquals("2026-03-01 12:00", formatLocal(1_772_366_400_000))
        assertEquals("2026-12-31 23:30", formatLocal(1_798_759_800_000))
        assertEquals("1969-12-31 23:00", formatLocal(-3_600_000), "before the epoch")
    }

    @Test
    fun instantsBeforeTheEpochRoundDownToTheirDayAndMinute() {
        assertEquals("1969-12-31 23:59", formatLocal(-1_000), "a second before the epoch")
        assertEquals("1969-12-31 00:00", formatLocal(-86_400_000), "an exact day before")
        assertEquals(-1L, localEpochDay(-1))
        assertEquals(-1L, localEpochDay(-86_400_000))
        // Before the year 0 the era of the calendar algorithm is negative: inexact, then exact.
        assertEquals("0-02-29 00:00", formatLocal(-719_469L * 86_400_000), "the day before 0000-03-01")
        assertEquals("-400-03-01 00:00", formatLocal(-865_565L * 86_400_000), "an exact era before")
    }

    @Test
    fun theZoneMovesTheDayAndTheClock() {
        utcOffsetMinutes = { 120 }
        assertEquals("2027-01-01 01:30", formatLocal(1_798_759_800_000))
        utcOffsetMinutes = { -300 }
        assertEquals("2026-01-14 19:05", formatLocal(1_768_435_500_000))
        assertEquals(20_467L, localEpochDay(1_768_435_500_000))
    }

    @Test
    fun aTimeOfTheDayShowsTheClockAloneAndAnotherDayTheDate() {
        val at = 1_793_221_200_000
        val today = localEpochDay(at)
        assertEquals("21:00", formatEventTime(at, today))
        assertEquals("2026-10-28 21:00", formatEventTime(at, today + 1))
        assertEquals("2026-10-28 21:00", formatEventTime(at, null))
    }
}
