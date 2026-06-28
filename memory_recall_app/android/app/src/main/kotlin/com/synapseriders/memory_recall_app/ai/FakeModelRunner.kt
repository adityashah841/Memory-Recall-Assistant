package com.synapseriders.memory_recall_app.ai

import android.graphics.Bitmap
import com.synapseriders.memory_recall_app.data.EmbeddingCodec
import java.util.Random

/**
 * Deterministic [OnDeviceModelRunner] for unit tests / CI only (IMPLEMENTATION_PLAN.md §8.2, §19).
 *
 * **Never used in the demo build** — `CompositeModelRunner` is the production runner. Outputs are
 * reproducible and input-dependent (same Bitmap → same embedding) so identity-matching tests are
 * stable without any real model files.
 */
class FakeModelRunner : OnDeviceModelRunner {

    override fun detectFaces(frame: Bitmap): List<FaceBox> {
        // One centered face covering ~40% of the frame.
        val w = frame.width * 0.4f
        val h = frame.height * 0.4f
        return listOf(FaceBox((frame.width - w) / 2f, (frame.height - h) / 2f, w, h, 0.99f))
    }

    override fun extractLandmarks(faceCrop: Bitmap): FloatArray {
        val w = faceCrop.width.toFloat()
        val h = faceCrop.height.toFloat()
        // L-eye, R-eye, nose, L-mouth, R-mouth at plausible relative positions.
        return floatArrayOf(
            0.35f * w, 0.40f * h,
            0.65f * w, 0.40f * h,
            0.50f * w, 0.55f * h,
            0.38f * w, 0.72f * h,
            0.62f * w, 0.72f * h,
        )
    }

    override fun embedFace(alignedFace: Bitmap): FloatArray {
        val rnd = Random(hashOf(alignedFace).toLong())
        val v = FloatArray(EMBED_DIM) { rnd.nextFloat() * 2f - 1f }
        return EmbeddingCodec.l2Normalize(v)
    }

    override fun transcribe(pcmAudio: ShortArray, sampleRate: Int): String =
        "(fake transcript of ${pcmAudio.size} samples)"

    override fun generate(prompt: String, maxTokens: Int): String = "(fake completion)"

    override fun warmUp() { /* no-op */ }

    private fun hashOf(bmp: Bitmap): Int {
        val n = 8
        val small = Bitmap.createScaledBitmap(bmp, n, n, true)
        val px = IntArray(n * n)
        small.getPixels(px, 0, n, 0, 0, n, n)
        if (small !== bmp) small.recycle()
        return px.contentHashCode()
    }

    private companion object {
        const val EMBED_DIM = 512
    }
}
