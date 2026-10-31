package com.camimu.orient.sensors

/** Measures an event rate (Hz) over a sliding one-second window. Single-thread use. */
class RateMeter {
    private var windowStartNs = 0L
    private var count = 0
    @Volatile var hz = 0.0
        private set

    fun tick(tNs: Long) {
        if (windowStartNs == 0L) windowStartNs = tNs
        count++
        val dt = tNs - windowStartNs
        if (dt >= 1_000_000_000L) {
            hz = count * 1e9 / dt
            count = 0
            windowStartNs = tNs
        }
    }
}
