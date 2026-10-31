package com.camimu.orient.sensors

import android.os.SystemClock

/**
 * Puts sensor timestamps on the elapsedRealtimeNanos clock. Most devices already use it; some
 * report CLOCK_MONOTONIC (System.nanoTime) instead. Decided from the first event.
 */
class TimeSync {
    enum class Clock { UNKNOWN, ELAPSED_REALTIME, MONOTONIC }

    @Volatile var sensorClock = Clock.UNKNOWN
        private set
    private var offsetNs = 0L

    fun toElapsedRealtimeNs(sensorTs: Long): Long {
        if (sensorClock == Clock.UNKNOWN) decide(sensorTs)
        return sensorTs + offsetNs
    }

    private fun decide(ts: Long) {
        val elapsed = SystemClock.elapsedRealtimeNanos()
        val mono = System.nanoTime()
        if (elapsed - ts in MIN_LAG_NS..MAX_LAG_NS) {
            sensorClock = Clock.ELAPSED_REALTIME
        } else if (mono - ts in MIN_LAG_NS..MAX_LAG_NS) {
            sensorClock = Clock.MONOTONIC
            offsetNs = elapsed - mono
        } else {
            sensorClock = Clock.ELAPSED_REALTIME // unknown base; keep as-is (logged in meta.json)
        }
    }

    companion object {
        private const val MIN_LAG_NS = -5_000_000L
        private const val MAX_LAG_NS = 200_000_000L

        /** Offset to convert System.nanoTime-based timestamps to elapsedRealtimeNanos. */
        fun monotonicToElapsedOffsetNs(): Long = SystemClock.elapsedRealtimeNanos() - System.nanoTime()
    }
}
