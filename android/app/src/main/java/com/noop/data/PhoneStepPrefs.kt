package com.noop.data

import android.content.Context
import com.noop.analytics.PhoneStepLedger

/**
 * On-device preference + baseline surface for phone step counting.
 *
 * Two different things live here, deliberately kept apart:
 *  - the USER CHOICES ([enabled], [writeToHealthConnect]), which are settings; and
 *  - the SENSOR BASELINE ([baseline]), which is not a setting at all but the last platform reading this
 *    device managed to take.
 *
 * The baseline must never cross the `.noopbak` boundary, and is deliberately absent from
 * [BackupSettingsCodec.WHITELIST]. It describes the step counter of ONE phone as it was on ONE boot; a
 * restore onto a different phone would pair a foreign cumulative counter with a fresh one and the first
 * difference would be a fabricated delta. A restored install simply takes one fresh baseline.
 *
 * Default OFF: nothing is read from the sensor, and no WorkManager wake is scheduled, until the user
 * turns it on. Mirrors the [com.noop.analytics.NapPrefs] shape (Context-only, no dependencies) so the
 * background worker can read it without pulling in the UI layer.
 */
object PhoneStepPrefs {

    private const val PREFS = "noop_prefs"
    private const val KEY_ENABLED = "noop.phoneSteps.enabled"
    private const val KEY_WRITE_HC = "noop.phoneSteps.writeHealthConnect"
    private const val KEY_LAST_TS = "noop.phoneSteps.lastTs"
    private const val KEY_LAST_COUNTER = "noop.phoneSteps.lastCounter"
    private const val KEY_LAST_BOOT = "noop.phoneSteps.lastBootEpoch"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Whether the phone's step counter is being sampled at all. Default OFF (opt-in). */
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    /**
     * Flip the feature. Turning it OFF also drops the baseline, so a re-enable starts from a fresh
     * reading: the counter has almost certainly moved (and may have reset across a reboot) while the
     * feature was off, and attributing that gap to the day of re-enabling would invent a step total.
     */
    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
        if (!on) clearBaseline(context)
    }

    /** Opt-in echo of the counted steps into Health Connect, so other apps can see them. Default OFF. */
    fun writeToHealthConnect(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WRITE_HC, false)

    fun setWriteToHealthConnect(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_WRITE_HC, on).apply()
    }

    /** The last reading this device took, or null before the first one / after a reset. */
    fun baseline(context: Context): PhoneStepLedger.Sample? {
        val p = prefs(context)
        val ts = p.getLong(KEY_LAST_TS, 0L)
        if (ts <= 0L) return null
        return PhoneStepLedger.Sample(
            ts = ts,
            counter = p.getLong(KEY_LAST_COUNTER, 0L),
            bootEpoch = p.getLong(KEY_LAST_BOOT, 0L),
        )
    }

    /**
     * Persist [sample] as the new baseline. Uses `commit()` rather than `apply()` on purpose: the caller
     * writes the day rows only after this returns, and a delta that is remembered but never written costs
     * one interval of steps, while a delta written but never remembered would be added twice.
     */
    fun setBaseline(context: Context, sample: PhoneStepLedger.Sample) {
        prefs(context).edit()
            .putLong(KEY_LAST_TS, sample.ts)
            .putLong(KEY_LAST_COUNTER, sample.counter)
            .putLong(KEY_LAST_BOOT, sample.bootEpoch)
            .commit()
    }

    fun clearBaseline(context: Context) {
        prefs(context).edit()
            .remove(KEY_LAST_TS)
            .remove(KEY_LAST_COUNTER)
            .remove(KEY_LAST_BOOT)
            .commit()
    }
}
