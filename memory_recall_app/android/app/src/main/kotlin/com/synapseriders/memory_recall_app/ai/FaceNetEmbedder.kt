package com.synapseriders.memory_recall_app.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.synapseriders.memory_recall_app.data.EmbeddingCodec
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.sqrt

/**
 * FaceNet-512 identity embedder on LiteRT (formerly TensorFlow Lite) — the non-Qualcomm stand-in for
 * CavaFace. Same contract: a face crop in, an L2-normalized `float32[512]` out, so the database schema
 * and dot-product matching are unchanged.
 *
 * Model: `assets/litert/facenet_512.tflite` — Inception-ResNet-v1 FaceNet (VGGFace2), 160×160 RGB
 * input, per-image standardization `(x - mean) / std`. See `assets/litert/NOTICE.md` for provenance.
 *
 * The interpreter is not thread-safe, so inference is serialized. Never throws: returns an empty
 * array on any failure.
 */
class FaceNetEmbedder(private val context: Context) {

    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var loadFailed = false

    fun warmUp() {
        if (interpreter != null || loadFailed) return
        synchronized(this) {
            if (interpreter != null || loadFailed) return
            interpreter = try {
                Interpreter(loadModel(), Interpreter.Options().setNumThreads(NUM_THREADS)).also {
                    Log.i(TAG, "facenet_512 loaded: in=${it.getInputTensor(0).shape().contentToString()} " +
                        "out=${it.getOutputTensor(0).shape().contentToString()}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "facenet_512 load failed — identity disabled", t)
                loadFailed = true
                null
            }
        }
    }

    /** L2-normalized 512-d embedding of [face] (any size; resized to 160×160). Empty on failure. */
    fun embed(face: Bitmap): FloatArray {
        warmUp()
        val interp = interpreter ?: return FloatArray(0)
        return StageLatency.measure(StageLatency.FACE_EMBEDDING) {
            try {
                val input = preprocess(face)
                val output = Array(1) { FloatArray(EMBED_DIM) }
                synchronized(this) { interp.run(input, output) }
                EmbeddingCodec.l2Normalize(output[0])
            } catch (t: Throwable) {
                Log.w(TAG, "embed failed", t)
                FloatArray(0)
            }
        }
    }

    fun close() {
        synchronized(this) {
            try { interpreter?.close() } catch (_: Throwable) {}
            interpreter = null
        }
    }

    /** NHWC float32 `[1,160,160,3]`, RGB, standardized per image (FaceNet's prewhitening). */
    private fun preprocess(face: Bitmap): ByteBuffer {
        val scaled = if (face.width == INPUT_SIZE && face.height == INPUT_SIZE) face
            else Bitmap.createScaledBitmap(face, INPUT_SIZE, INPUT_SIZE, true)
        val px = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(px, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (scaled !== face) scaled.recycle()

        val values = FloatArray(px.size * 3)
        var i = 0
        for (p in px) {
            values[i++] = ((p shr 16) and 0xFF).toFloat()
            values[i++] = ((p shr 8) and 0xFF).toFloat()
            values[i++] = (p and 0xFF).toFloat()
        }
        val mean = values.average().toFloat()
        var sq = 0.0
        for (v in values) sq += ((v - mean) * (v - mean)).toDouble()
        val std = max(sqrt(sq / values.size).toFloat(), 1f / sqrt(values.size.toFloat()))

        val buf = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
        for (v in values) buf.putFloat((v - mean) / std)
        buf.rewind()
        return buf
    }

    private fun loadModel(): MappedByteBuffer =
        context.assets.openFd(MODEL_ASSET).use { fd ->
            FileInputStream(fd.fileDescriptor).use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }

    private companion object {
        const val TAG = "FaceNetEmbedder"
        const val MODEL_ASSET = "litert/facenet_512.tflite"
        const val INPUT_SIZE = 160
        const val EMBED_DIM = 512
        const val NUM_THREADS = 4
    }
}
