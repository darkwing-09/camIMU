package com.camimu.orient.logging

import android.util.Log
import com.camimu.orient.core.config.ConfigJson
import com.camimu.orient.core.io.CsvSchema
import com.camimu.orient.core.io.SessionMeta
import com.camimu.orient.core.model.ArCoreReading
import com.camimu.orient.core.model.ImuSample
import com.camimu.orient.core.model.OrientationOutput
import com.camimu.orient.core.model.VisionMeasurement
import com.camimu.orient.core.model.VisionUpdateOutcome
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Buffered, off-thread CSV logging. Producers never block; rows are dropped (and counted) if the queue is full. */
class SessionLogger(val dir: File, private val flushMs: Long) {
    private class Row(val file: String, val payload: Any)
    private class VisionRow(val m: VisionMeasurement, val o: VisionUpdateOutcome, val tApplied: Long, val seq: Long)
    private class EventRow(val t: Long, val seq: Long, val name: String, val detail: String)

    private val queue = ArrayBlockingQueue<Row>(QUEUE_CAPACITY)
    private val dropped = AtomicLong()
    @Volatile private var running = true
    private val writers = HashMap<String, BufferedWriter>()
    private val thread = Thread(::loop, "log").apply { isDaemon = true; start() }

    init {
        dir.mkdirs()
    }

    fun imu(s: ImuSample) = offer(Row(IMU, s))
    fun est(o: OrientationOutput) = offer(Row(EST, o))
    fun vision(m: VisionMeasurement, o: VisionUpdateOutcome, tApplied: Long, seq: Long) = offer(Row(VISION, VisionRow(m, o, tApplied, seq)))
    fun frame(formattedLine: String) = offer(Row(FRAMES, formattedLine))
    fun arcore(r: ArCoreReading) = offer(Row(ARCORE, r))
    fun event(tNs: Long, seq: Long, name: String, detail: String) = offer(Row(EVENTS, EventRow(tNs, seq, name, detail)))

    private fun offer(r: Row) {
        if (!queue.offer(r)) dropped.incrementAndGet()
    }

    /** Drains the queue, writes meta.json (with the dropped-row count) and closes all files. */
    fun close(meta: SessionMeta) {
        running = false
        thread.join(5000)
        drain()
        writers.values.forEach { runCatching { it.flush(); it.close() } }
        writers.clear()
        val m = meta.copy(droppedRows = dropped.get())
        File(dir, "meta.json").writeText(ConfigJson.encode(m))
        Log.i(TAG, "session closed: ${dir.absolutePath} dropped=${dropped.get()}")
    }

    private fun loop() {
        var lastFlush = System.currentTimeMillis()
        while (running) {
            val r = queue.poll(100, TimeUnit.MILLISECONDS)
            if (r != null) write(r)
            val now = System.currentTimeMillis()
            if (now - lastFlush >= flushMs) {
                writers.values.forEach { runCatching { it.flush() } }
                lastFlush = now
            }
        }
    }

    private fun drain() {
        while (true) write(queue.poll() ?: return)
    }

    private fun write(r: Row) {
        try {
            val w = writers.getOrPut(r.file) {
                File(dir, r.file).bufferedWriter().also { it.write(headerFor(r.file)); it.newLine() }
            }
            val line = when (val p = r.payload) {
                is ImuSample -> CsvSchema.Imu.format(p)
                is OrientationOutput -> CsvSchema.Est.format(p)
                is VisionRow -> CsvSchema.Vision.format(p.m, p.o, p.tApplied, p.seq)
                is ArCoreReading -> CsvSchema.ArCore.format(p)
                is EventRow -> CsvSchema.Events.format(p.t, p.seq, p.name, p.detail)
                is String -> p
                else -> return
            }
            w.write(line)
            w.newLine()
        } catch (e: Exception) {
            Log.w(TAG, "write failed for ${r.file}: ${e.message}")
        }
    }

    private fun headerFor(file: String) = when (file) {
        IMU -> CsvSchema.Imu.HEADER
        EST -> CsvSchema.Est.HEADER
        VISION -> CsvSchema.Vision.HEADER
        FRAMES -> CsvSchema.VisionFrames.HEADER
        ARCORE -> CsvSchema.ArCore.HEADER
        else -> CsvSchema.Events.HEADER
    }

    companion object {
        private const val TAG = "CamIMU.Log"
        private const val QUEUE_CAPACITY = 20_000
        const val IMU = "imu.csv"
        const val EST = "est.csv"
        const val VISION = "vision.csv"
        const val FRAMES = "vision_frames.csv"
        const val ARCORE = "arcore.csv"
        const val EVENTS = "events.csv"
    }
}
