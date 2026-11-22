package com.noop.ingest

import android.content.Context
import com.noop.analytics.AnalyticsEngine
import com.noop.analytics.PhoneStepLedger
import com.noop.data.AppleDaily
import com.noop.data.PhoneStepPrefs
import com.noop.data.WhoopRepository
import java.util.TimeZone

/**
 * Takes one phone step reading and banks it as daily totals under [WhoopRepository.PHONE_STEPS_SOURCE].
 *
 * WHY `appleDaily`. That table is already the app's home for phone-side step totals - Health Connect and
 * Apple Health write there, [com.noop.ui.stepsForDay] already takes the max across its sources to avoid
 * double-counting a phone+watch pair (#589), and the WHOOP-4.0 calibration reference reads it. Writing a
 * new table would mean re-implementing all three, so the phone's count is simply a third source in the
 * existing one.
 *
 * ORDERING, AND WHY IT MATTERS. The baseline is persisted BEFORE the day rows. A kill between the two
 * costs at most one sampling interval of steps; the opposite order would let the next run re-add a delta
 * that was already banked, inflating the day permanently. Under-counting one interval is recoverable,
 * double-counting is not.
 */
object PhoneStepRecorder {

    /**
     * Sample once and bank whatever is attributable. Returns the number of steps newly written (0 when
     * this was the first reading, the counter had not moved, or no sensor/permission is available).
     * Never throws for an absent reading.
     */
    suspend fun record(context: Context, repo: WhoopRepository): Int {
        val sample = PhoneStepCounter.readOnce(context) ?: return 0
        val baseline = PhoneStepPrefs.baseline(context)
        val offsetSeconds = TimeZone.getDefault().getOffset(sample.ts * 1_000L) / 1_000L
        val allocations = PhoneStepLedger.allocate(baseline, sample, offsetSeconds)

        // See the class doc: remember the reading first, bank second.
        PhoneStepPrefs.setBaseline(context, sample)
        if (allocations.isEmpty()) return 0

        val echoToHealthConnect = PhoneStepPrefs.writeToHealthConnect(context)
        // Registered once per sample rather than once per day row: the device list only needs to know
        // this source exists.
        repo.upsertDevice(WhoopRepository.PHONE_STEPS_SOURCE, name = "This phone")
        var written = 0
        for (allocation in allocations) {
            val dayKey = AnalyticsEngine.dayString(allocation.dayStartTs, offsetSeconds)
            val existing = repo.appleDaily(
                WhoopRepository.PHONE_STEPS_SOURCE, dayKey, dayKey,
            ).firstOrNull()
            val total = (existing?.steps ?: 0) + allocation.steps
            repo.upsertAppleDaily(
                listOf(
                    existing?.copy(steps = total)
                        ?: AppleDaily(
                            deviceId = WhoopRepository.PHONE_STEPS_SOURCE,
                            day = dayKey,
                            steps = total,
                        ),
                ),
            )
            if (echoToHealthConnect) {
                // The DAY TOTAL, not this allocation: Health Connect upserts by clientRecordId, so
                // re-writing the same day with a growing total replaces rather than adds. Writing the
                // allocation instead would make each sample overwrite the previous one's steps.
                HealthConnectWriter.writePhoneStepDay(
                    context = context,
                    dayStartTs = allocation.dayStartTs,
                    total = total,
                )
            }
            written += allocation.steps
        }
        return written
    }
}
