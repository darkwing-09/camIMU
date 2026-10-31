package com.camimu.orient.core.math

import kotlin.math.abs
import kotlin.math.sqrt

/** Cyclic Jacobi eigen-decomposition for small symmetric matrices. */
object SymmetricEigen {
    /** [values] unsorted; [vectors] row-major n×n where column j is the eigenvector of values[j]. */
    class Result(val values: DoubleArray, val vectors: Array<DoubleArray>) {
        fun vector(j: Int) = DoubleArray(values.size) { vectors[it][j] }
        fun indexOfMax(): Int = values.indices.maxByOrNull { values[it] } ?: 0
    }

    fun jacobi(a: Array<DoubleArray>, maxSweeps: Int = 50, tol: Double = 1e-22): Result {
        val n = a.size
        require(a.all { it.size == n }) { "matrix must be square" }
        val m = Array(n) { a[it].copyOf() }
        val v = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
        repeat(maxSweeps) {
            var off = 0.0
            var scale = 0.0
            for (p in 0 until n) {
                scale += m[p][p] * m[p][p]
                for (q in p + 1 until n) off += m[p][q] * m[p][q]
            }
            if (off <= tol * (scale + 1e-300)) return Result(DoubleArray(n) { m[it][it] }, v)
            for (p in 0 until n) for (q in p + 1 until n) {
                if (abs(m[p][q]) < 1e-300) continue
                val theta = (m[q][q] - m[p][p]) / (2 * m[p][q])
                val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                val c = 1 / sqrt(t * t + 1)
                val s = t * c
                for (k in 0 until n) {
                    val kp = m[k][p]; val kq = m[k][q]
                    m[k][p] = c * kp - s * kq; m[k][q] = s * kp + c * kq
                }
                for (k in 0 until n) {
                    val pk = m[p][k]; val qk = m[q][k]
                    m[p][k] = c * pk - s * qk; m[q][k] = s * pk + c * qk
                }
                for (k in 0 until n) {
                    val kp = v[k][p]; val kq = v[k][q]
                    v[k][p] = c * kp - s * kq; v[k][q] = s * kp + c * kq
                }
            }
        }
        return Result(DoubleArray(n) { m[it][it] }, v)
    }
}
