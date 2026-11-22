package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PhoneStepLedger] - the pure kernel behind on-device phone step counting.
 *
 * The platform counter is exact, so most of what can go wrong is not the count but the PLACEMENT: which
 * local day a delta belongs to across a midnight, and what a reboot or a counter reset means. Those are
 * the cases pinned here. All timestamps are UTC unless the test says otherwise.
 */
class PhoneStepLedgerTest {

    private fun sample(ts: Long, counter: Long, bootEpoch: Long = 0L) =
        PhoneStepLedger.Sample(ts = ts, counter = counter, bootEpoch = bootEpoch)

    private val day = PhoneStepLedger.SECONDS_PER_DAY

    @Test
    fun firstReadingIsOnlyABaseline() {
        // Nothing to attribute: the counter's absolute value says nothing about when the steps happened.
        assertTrue(PhoneStepLedger.allocate(null, sample(1_000, 4_321), 0L).isEmpty())
    }

    @Test
    fun sameDayDeltaIsNotSplit() {
        val out = PhoneStepLedger.allocate(sample(1_000, 100), sample(1_300, 350), 0L)
        assertEquals(listOf(PhoneStepLedger.DayAllocation(0L, 250)), out)
    }

    @Test
    fun midnightStraddleSplitsAndSumsExactly() {
        // 23:00 -> 01:00, equal time either side of midnight, 1,200 steps.
        val day1 = 1_735_603_200L // 2026-01-01T00:00:00Z
        val prev = sample(day1 + 82_800L, 1_000L)   // 23:00
        val curr = sample(day1 + 90_000L, 2_200L)   // next day 01:00

        val out = PhoneStepLedger.allocate(prev, curr, 0L)

        assertEquals(2, out.size)
        assertEquals(day1, out[0].dayStartTs)
        assertEquals(day1 + day, out[1].dayStartTs)
        // Half the window each way.
        assertEquals(600, out[0].steps)
        assertEquals(600, out[1].steps)
        // The invariant that matters: nothing created, nothing lost.
        assertEquals(1_200, out.sumOf { it.steps })
    }

    @Test
    fun unevenStraddleStillSumsToTheExactDelta() {
        // 22:00 -> 00:30: two hours on day 1, half an hour on day 2.
        val day1 = 1_735_603_200L
        val prev = sample(day1 + 79_200L, 0L)
        val curr = sample(day1 + 88_200L, 1_000L)

        val out = PhoneStepLedger.allocate(prev, curr, 0L)

        assertEquals(1_000, out.sumOf { it.steps })
        // 9000 s of window, 7200 s on day 1 => 800 steps, the rest 200.
        assertEquals(800, out.first { it.dayStartTs == day1 }.steps)
        assertEquals(200, out.first { it.dayStartTs == day1 + day }.steps)
    }

    @Test
    fun rebootAttributesFromTheBootInstant() {
        // The pre-reboot counter is unusable, so only the post-boot count is attributed, and it is
        // measured from the boot instant rather than from the stale previous reading.
        val prev = sample(ts = 1_000, counter = 5_000, bootEpoch = 100)
        val curr = sample(ts = 2_000, counter = 300, bootEpoch = 1_900)

        val out = PhoneStepLedger.allocate(prev, curr, 0L)

        assertEquals(listOf(PhoneStepLedger.DayAllocation(0L, 300)), out)
    }

    @Test
    fun rebootIsNotTreatedAsANegativeDelta() {
        // Same boot epoch but a counter that went backwards: a sensor/firmware reset, not a reboot.
        val prev = sample(ts = 500, counter = 900)
        val curr = sample(ts = 800, counter = 100)

        val out = PhoneStepLedger.allocate(prev, curr, 0L)

        assertEquals(listOf(PhoneStepLedger.DayAllocation(0L, 100)), out)
    }

    @Test
    fun implausibleDeltaIsClampedToTheGaitCeiling() {
        // 100,000 steps in 10 seconds is corruption, not walking; 4 steps/s over 10 s is the ceiling.
        val out = PhoneStepLedger.allocate(sample(0, 0), sample(10, 100_000), 0L)
        assertEquals(listOf(PhoneStepLedger.DayAllocation(0L, 40)), out)
    }

    @Test
    fun flatCounterProducesNothing() {
        assertTrue(PhoneStepLedger.allocate(sample(0, 500), sample(600, 500), 0L).isEmpty())
    }

    @Test
    fun negativeCounterIsIgnored() {
        assertTrue(PhoneStepLedger.allocate(sample(0, 500), sample(600, -1), 0L).isEmpty())
    }

    @Test
    fun multiDayGapIsSpreadAcrossEveryDay() {
        // Three full days of silence, 3,000 steps: the platform knows the total but not the split, so it
        // is spread uniformly - and still sums exactly.
        val out = PhoneStepLedger.allocate(sample(0, 0), sample(3 * day, 3_000), 0L)

        assertEquals(3, out.size)
        assertEquals(listOf(0L, day, 2 * day), out.map { it.dayStartTs })
        assertEquals(listOf(1_000, 1_000, 1_000), out.map { it.steps })
        assertEquals(3_000, out.sumOf { it.steps })
    }

    @Test
    fun localDayStartRespectsTheOffset() {
        // 2026-01-01T00:00Z is 01:00 in UTC+1, so the local day began an hour earlier.
        val ts = 1_735_603_200L
        assertEquals(ts - 3_600L, PhoneStepLedger.localDayStart(ts, 3_600L))
        assertEquals(ts, PhoneStepLedger.localDayStart(ts, 0L))
    }

    @Test
    fun clockStepBackwardsDoesNotProduceANegativeWindow() {
        // A reading whose boot instant is in the future (wall clock stepped back) still yields one
        // allocation at the reading's own day rather than a negative-length window.
        val prev = sample(ts = 1_000, counter = 0)
        val curr = sample(ts = 900, counter = 50, bootEpoch = 5_000)

        val out = PhoneStepLedger.allocate(prev, curr, 0L)

        assertEquals(1, out.size)
        assertEquals(50, out.sumOf { it.steps })
    }
}
