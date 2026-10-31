package com.camimu.orient.core.vision

import com.camimu.orient.core.math.Quaternion
import com.camimu.orient.core.math.SymmetricEigen
import com.camimu.orient.core.math.Vec3
import kotlin.math.abs

/** Horn's closed-form solution to Wahba's problem (Architecture §6.5). */
object WahbaSolver {
    /**
     * Best rotation q21 with b2 ≈ q21 · b1 (least squares, optional weights).
     * @return null for fewer than 2 points or a degenerate configuration.
     */
    fun solve(b1: List<Vec3>, b2: List<Vec3>, weights: DoubleArray? = null): Quaternion? {
        require(b1.size == b2.size)
        if (b1.size < 2) return null
        val s = Array(3) { DoubleArray(3) }
        for (i in b1.indices) {
            val w = weights?.get(i) ?: 1.0
            val p = b1[i]; val q = b2[i]
            for (a in 0 until 3) for (b in 0 until 3) s[a][b] += w * p[a] * q[b]
        }
        val sxx = s[0][0]; val sxy = s[0][1]; val sxz = s[0][2]
        val syx = s[1][0]; val syy = s[1][1]; val syz = s[1][2]
        val szx = s[2][0]; val szy = s[2][1]; val szz = s[2][2]
        val n = arrayOf(
            doubleArrayOf(sxx + syy + szz, syz - szy, szx - sxz, sxy - syx),
            doubleArrayOf(syz - szy, sxx - syy - szz, sxy + syx, szx + sxz),
            doubleArrayOf(szx - sxz, sxy + syx, -sxx + syy - szz, syz + szy),
            doubleArrayOf(sxy - syx, szx + sxz, syz + szy, -sxx - syy + szz),
        )
        val eig = SymmetricEigen.jacobi(n)
        val sorted = eig.values.sortedDescending()
        if (abs(sorted[0] - sorted[1]) < 1e-12 * (abs(sorted[0]) + 1e-300)) return null
        val v = eig.vector(eig.indexOfMax())
        val q = Quaternion(v[0], v[1], v[2], v[3])
        if (!q.isFinite() || q.norm() < 1e-12) return null
        return q.normalized().canonical()
    }
}
