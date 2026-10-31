package com.camimu.orient.core.vision

import com.camimu.orient.core.model.Px

/** Spreads features over a grid so no single region (obstacle) dominates (obstacle layer 3). */
class GridBucketer(private val width: Int, private val height: Int, private val cols: Int, private val rows: Int) {
    val cellCount get() = cols * rows

    fun cellOf(p: Px): Int {
        val c = ((p.u / width) * cols).toInt().coerceIn(0, cols - 1)
        val r = ((p.v / height) * rows).toInt().coerceIn(0, rows - 1)
        return r * cols + c
    }

    /** [candidates] best-first. Returns those to add so each cell holds ≤ [maxPerCell] including [existing]. */
    fun select(candidates: List<Px>, existing: List<Px>, maxPerCell: Int, maxTotal: Int): List<Px> {
        val counts = IntArray(cellCount)
        for (p in existing) counts[cellOf(p)]++
        val out = ArrayList<Px>()
        var total = existing.size
        for (p in candidates) {
            if (total >= maxTotal) break
            val c = cellOf(p)
            if (counts[c] < maxPerCell) {
                counts[c]++
                out += p
                total++
            }
        }
        return out
    }
}
