package com.synapseriders.memory_recall_app.capture

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Detects meaningful scene change between ticks (IMPLEMENTATION_PLAN.md §10.4 skip condition).
 *
 * Downscales each frame to a small grayscale signature and compares mean absolute difference against
 * [threshold] (0..1). The first frame always counts as changed. The comparison core
 * ([changedSignature]) is pure and unit-tested.
 */
class SceneChangeDetector(private val threshold: Float = 0.10f) {

    private var last: IntArray? = null

    /** True if [frame] differs meaningfully from the previous one; updates internal state. */
    fun changed(frame: Bitmap): Boolean = changedSignature(signature(frame))

    /** Pure core: compare a new [sig] against the stored one, update, and report change. */
    internal fun changedSignature(sig: IntArray): Boolean {
        val prev = last
        last = sig
        if (prev == null || prev.size != sig.size) return true
        return meanAbsNormalizedDiff(prev, sig) > threshold
    }

    fun reset() { last = null }

    internal fun meanAbsNormalizedDiff(a: IntArray, b: IntArray): Float {
        var sum = 0L
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum.toFloat() / (a.size * 255f)
    }

    /** N×N grayscale (0..255) signature of [frame]. */
    private fun signature(frame: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(frame, N, N, true)
        val px = IntArray(N * N)
        small.getPixels(px, 0, N, 0, 0, N, N)
        if (small !== frame) small.recycle()
        return IntArray(N * N) { i ->
            val p = px[i]
            (((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3)
        }
    }

    private companion object {
        const val N = 16
    }
}
