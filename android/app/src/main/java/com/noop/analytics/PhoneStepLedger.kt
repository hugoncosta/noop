package com.noop.analytics

/**
 * Turns Android's cumulative `TYPE_STEP_COUNTER` readings into per-local-day step allocations.
 *
 * WHY THIS IS NOT A PEDOMETER EITHER. `TYPE_STEP_COUNTER` is the phone's hardware step counter: a
 * monotonic count since boot, incremented by the platform, not by us. We never see individual steps, so
 * this object does no step detection - it differences two readings and decides which local day the
 * difference belongs to. That difference is exact, because it is the platform's own count; only the split
 * across a midnight falling between two readings is apportioned, and that apportionment is the
 * uniform-rate assumption documented on [allocate].
 *
 * WHY THE SPLIT EXISTS AT ALL. A reading can arrive hours after the previous one: WorkManager batches,
 * Doze defers, and the process may be dead for a night. Attributing a whole overnight gap to the day of
 * the newer reading would move yesterday evening's walk onto today. Splitting by elapsed wall-clock time
 * keeps the day totals summing to the platform's exact count while putting most of a straddling delta on
 * the side of midnight it actually happened on. Nothing is invented: a delta that never crosses midnight
 * is never split.
 *
 * WHY REBOOTS ARE HANDLED HERE. The counter restarts at zero on reboot, so a naive subtraction would
 * produce a large negative delta (dropped) or, worse, a later wrap-shaped positive one. [Sample.bootEpoch]
 * is the wall-clock instant of the boot the reading belongs to, computed as `now - elapsedRealtime`, so a
 * changed boot is detectable and the post-boot reading is attributed from the boot instant instead of
 * from the previous reading.
 *
 * Kotlin-only for now: Android is the platform with `TYPE_STEP_COUNTER`. The iOS twin would be
 * `CMPedometer`, whose own cumulative-since-boot semantics differ, so no Swift twin is claimed here.
 */
object PhoneStepLedger {

    /** One reading of the platform counter. */
    data class Sample(
        /** Unix seconds, wall clock, at the moment the reading was taken. */
        val ts: Long,
        /** `TYPE_STEP_COUNTER`, cumulative since [bootEpoch]. */
        val counter: Long,
        /** Unix seconds of the boot this counter belongs to (`now - elapsedRealtime`). */
        val bootEpoch: Long,
    )

    /** Steps attributed to the local day that starts at [dayStartTs] (unix seconds). */
    data class DayAllocation(val dayStartTs: Long, val steps: Int)

    const val SECONDS_PER_DAY = 86_400L

    /**
     * Gait ceiling used to reject corruption rather than to detect walking. Four steps/second is already
     * 240 steps/minute, the same plausibility ceiling [StepsCounter] applies to the strap's counter, so a
     * delta implying more than this over the elapsed wall clock is treated as a bad reading and clamped.
     */
    const val MAX_STEPS_PER_SECOND = 4.0

    /** Start (unix seconds) of the local day containing [ts], for a fixed [tzOffsetSeconds] east of UTC. */
    fun localDayStart(ts: Long, tzOffsetSeconds: Long): Long =
        Math.floorDiv(ts + tzOffsetSeconds, SECONDS_PER_DAY) * SECONDS_PER_DAY - tzOffsetSeconds

    /**
     * Allocate the steps between [previous] and [current] to local days.
     *
     * Returns an empty list when there is nothing to attribute: no previous reading (this one is only a
     * baseline), a non-positive delta, or a counter that did not move. When the window spans more than one
     * local day the accepted total is divided in proportion to the wall-clock time spent in each day, with
     * largest-remainder rounding so the allocations sum EXACTLY to the accepted total - no step is created
     * or lost, only placed.
     *
     * A reboot (or a counter that went backwards without one) attributes the whole post-boot counter,
     * measured from the boot instant rather than from [previous], because the steps taken before the reboot
     * are no longer representable by any reading we can take.
     */
    fun allocate(previous: Sample?, current: Sample, tzOffsetSeconds: Long): List<DayAllocation> {
        if (current.counter < 0L) return emptyList()
        if (previous == null) return emptyList()

        val rebooted = previous.bootEpoch != current.bootEpoch
        val reset = current.counter < previous.counter
        val windowStart = if (rebooted) current.bootEpoch else previous.ts
        val rawTotal = when {
            rebooted -> current.counter
            reset -> current.counter
            else -> current.counter - previous.counter
        }
        if (rawTotal <= 0L) return emptyList()

        // A boot instant after the reading itself means a clock step; treat the reading as instantaneous
        // rather than producing a negative window.
        val start = windowStart.coerceAtMost(current.ts)
        val elapsed = current.ts - start
        if (elapsed <= 0L) {
            return listOf(DayAllocation(localDayStart(current.ts, tzOffsetSeconds), clampToInt(rawTotal)))
        }

        val plausible = (elapsed * MAX_STEPS_PER_SECOND).toLong()
        val accepted = rawTotal.coerceAtMost(plausible).coerceAtMost(Int.MAX_VALUE.toLong())
        if (accepted <= 0L) return emptyList()

        val dayStarts = ArrayList<Long>(2)
        val weights = ArrayList<Long>(2)
        var cursor = start
        while (cursor < current.ts) {
            val dayStart = localDayStart(cursor, tzOffsetSeconds)
            val segmentEnd = minOf(dayStart + SECONDS_PER_DAY, current.ts)
            if (segmentEnd <= cursor) break
            dayStarts.add(dayStart)
            weights.add(segmentEnd - cursor)
            cursor = segmentEnd
        }
        if (dayStarts.isEmpty()) {
            return listOf(DayAllocation(localDayStart(current.ts, tzOffsetSeconds), accepted.toInt()))
        }
        if (dayStarts.size == 1) {
            return listOf(DayAllocation(dayStarts[0], accepted.toInt()))
        }

        val weightSum = weights.sum()
        val exact = DoubleArray(weights.size) { accepted.toDouble() * weights[it] / weightSum }
        val steps = IntArray(weights.size) { exact[it].toInt() }
        var remainder = accepted.toInt() - steps.sum()
        if (remainder > 0) {
            // Largest fractional part first; index breaks ties so the result is deterministic.
            val order = weights.indices.sortedWith(
                compareByDescending<Int> { exact[it] - steps[it] }.thenBy { it },
            )
            var i = 0
            while (remainder > 0) {
                steps[order[i % order.size]]++
                remainder--
                i++
            }
        }

        val out = ArrayList<DayAllocation>(steps.size)
        for (i in steps.indices) {
            if (steps[i] > 0) out.add(DayAllocation(dayStarts[i], steps[i]))
        }
        return out
    }

    private fun clampToInt(value: Long): Int =
        value.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}
