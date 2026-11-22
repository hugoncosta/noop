package com.noop.ingest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.noop.analytics.PhoneStepLedger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Reads Android's hardware step counter once, for [PhoneStepRecorder] to difference against the previous
 * reading.
 *
 * WHY `TYPE_STEP_COUNTER` AND NOT `TYPE_STEP_DETECTOR`. The counter is cumulative since boot and is
 * maintained by the platform's low-power sensor hub, so a single reading is enough to learn how many steps
 * happened since the last one - no listener has to stay registered for the count to be complete. The
 * detector only emits per-step events while a listener is alive, which would make every interval the app
 * was not running a permanent gap.
 *
 * The consequence to keep in mind: a reading only tells us the TOTAL since boot, never when. That is why
 * the placement work lives in [PhoneStepLedger] rather than here.
 */
object PhoneStepCounter {

    /**
     * How long to wait for the platform to deliver a reading. The sensor hub answers almost immediately;
     * this bound exists so a wedged or absent sensor cannot hold a worker's coroutine open.
     */
    private const val READ_TIMEOUT_MS = 10_000L

    /**
     * Whether the step counter may be read at all. `ACTIVITY_RECOGNITION` is a runtime permission from
     * Android 10 (API 29); on API 26-28 the sensor needs no permission, and [minSdk] is 26.
     */
    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACTIVITY_RECOGNITION,
            ) == PackageManager.PERMISSION_GRANTED

    /** Whether this phone actually has a step counter. Not every device does. */
    fun isAvailable(context: Context): Boolean {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        return sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
    }

    /**
     * Take one reading, or null when the permission is missing, the sensor is absent, the platform never
     * delivered a value, or registration failed. Never throws: a missing reading must degrade to "no
     * steps this interval", not to a failed worker.
     */
    suspend fun readOnce(context: Context): PhoneStepLedger.Sample? {
        if (!hasPermission(context)) return null
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return null
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null

        return withTimeoutOrNull(READ_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                // A Handler is required: a coroutine thread has no Looper of its own, and the platform
                // delivers sensor callbacks on the Looper of the registering thread unless one is given.
                val handler = Handler(Looper.getMainLooper())
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sm.unregisterListener(this)
                        if (!cont.isActive) return
                        val nowMs = System.currentTimeMillis()
                        cont.resume(
                            PhoneStepLedger.Sample(
                                ts = nowMs / 1000L,
                                counter = event.values.firstOrNull()?.toLong() ?: 0L,
                                // elapsedRealtime counts from boot including deep sleep, so subtracting
                                // it from the wall clock names the boot instant.
                                bootEpoch = (nowMs - SystemClock.elapsedRealtime()) / 1000L,
                            ),
                        )
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }

                cont.invokeOnCancellation { sm.unregisterListener(listener) }
                if (!sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }
}
