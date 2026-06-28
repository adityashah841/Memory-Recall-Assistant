package com.synapseriders.memory_recall_app.ai

import android.graphics.Bitmap

/**
 * Bitmap → tensor preprocessing shared by the model runners (IMPLEMENTATION_PLAN.md §2.1, §3.2).
 *
 * CavaFace's contract is exact (§2.1): resize 112×112, CHW, `pixel = (pixel/127.5) - 1.0`. The
 * generic helpers let the detection/landmark runners reuse the same path with their own input size
 * and normalization (those input sizes are model-specific and flagged where used).
 */
object ImagePreprocess {

    const val CAVAFACE_SIZE = 112

    /** CavaFace input: `float32[1,3,112,112]`, CHW (R,G,B planes), normalized to [-1, 1] (§2.1). */
    fun cavaFace(face: Bitmap): FloatArray =
        toChwFloat(face, CAVAFACE_SIZE, CAVAFACE_SIZE, mean = 127.5f, scale = 1f / 127.5f)

    /**
     * Generic CHW float tensor: resize [src] to [w]×[h], emit planar R,G,B with
     * `value = (channel - mean) * scale`. Length = 3*w*h (caller prepends the N=1 dim via [shape]).
     */
    fun toChwFloat(src: Bitmap, w: Int, h: Int, mean: Float, scale: Float): FloatArray {
        val resized = if (src.width == w && src.height == h) src else src.scale(w, h)
        val pixels = IntArray(w * h)
        resized.getPixels(pixels, 0, w, 0, 0, w, h)
        val plane = w * h
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            out[i] = (((p shr 16) and 0xFF) - mean) * scale          // R plane
            out[plane + i] = (((p shr 8) and 0xFF) - mean) * scale   // G plane
            out[2 * plane + i] = ((p and 0xFF) - mean) * scale       // B plane
        }
        if (resized !== src) resized.recycle()
        return out
    }

    /** NCHW shape vector for a single image. */
    fun nchwShape(w: Int, h: Int): LongArray = longArrayOf(1, 3, h.toLong(), w.toLong())

    private fun Bitmap.scale(w: Int, h: Int): Bitmap = Bitmap.createScaledBitmap(this, w, h, true)
}
