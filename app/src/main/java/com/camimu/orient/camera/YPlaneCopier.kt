package com.camimu.orient.camera

import java.nio.ByteBuffer

/** Copies a Y (luma) plane into a tightly packed array, removing row-stride padding. */
object YPlaneCopier {
    fun copy(buf: ByteBuffer, rowStride: Int, width: Int, height: Int, out: ByteArray) {
        require(out.size >= width * height)
        if (rowStride == width) {
            buf.rewind()
            buf.get(out, 0, width * height)
        } else {
            for (row in 0 until height) {
                buf.position(row * rowStride)
                buf.get(out, row * width, width)
            }
        }
    }
}
