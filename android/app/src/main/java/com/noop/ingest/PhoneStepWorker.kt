package com.noop.ingest

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.noop.NoopApplication
import com.noop.data.PhoneStepPrefs
import java.util.concurrent.TimeUnit

/**
 * The periodic wake behind opt-in phone step counting.
 *
 * WHY A WORKER RATHER THAN A LISTENER. The platform's step counter is cumulative since boot, so one
 * reading is enough to learn how many steps happened since the last one - nothing has to stay registered
 * for the count to be complete. A worker therefore gets the same total as a resident foreground service
 * at a fraction of the battery and with no notification, at the cost of coarser PLACEMENT (see
 * [com.noop.analytics.PhoneStepLedger.allocate], which splits a straddling delta by elapsed time).
 *
 * WorkManager's floor for periodic work is 15 minutes, and Doze may defer a run well past it. That is
 * acceptable here for the same reason: the total survives deferral, only the day/hour attribution of a
 * deferred gap is approximate.
 *
 * KEEP, not REPLACE: re-enqueuing on every launch with REPLACE would restart the period, so a user who
 * opens NOOP often could never reach a run.
 */
class PhoneStepWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!PhoneStepPrefs.enabled(app)) return Result.success()
        if (!PhoneStepCounter.hasPermission(app)) return Result.success()
        runCatching {
            PhoneStepRecorder.record(app, (app as NoopApplication).repository)
        }
        // Never retry: a missed reading is covered by the next period, and the baseline ordering in
        // [PhoneStepRecorder] means a late run cannot double-count.
        return Result.success()
    }

    companion object {
        private const val WORK = "noop.phoneSteps"

        /** WorkManager's minimum periodic interval; also the placement granularity of a delta. */
        const val INTERVAL_MINUTES = 15L

        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<PhoneStepWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES,
            ).build()
            runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }

        fun cancel(context: Context) {
            runCatching {
                WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK)
            }
        }
    }
}
