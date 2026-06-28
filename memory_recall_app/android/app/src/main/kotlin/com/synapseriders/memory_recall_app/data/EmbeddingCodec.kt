package com.synapseriders.memory_recall_app.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Converts face embeddings to/from the on-disk representation: generic `float32[]` <-> little-endian
 * blob, with L2 normalization (IMPLEMENTATION_PLAN.md Section 5, v4 CavaFace embedding schema —
 * Checkpoint 2.6).
 *
 * The stored vector is `person_profile.face_embedding` (512-d, CavaFace space). It is L2-normalized
 * at write time so cosine similarity reduces to a dot product during the embedding scan (Section 7).
 */
object EmbeddingCodec {

    fun toBlob(vector: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (v in vector) buf.putFloat(v)
        return buf.array()
    }

    fun fromBlob(blob: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(blob.size / Float.SIZE_BYTES) { buf.float }
    }

    fun l2Normalize(vector: FloatArray): FloatArray {
        var sumSq = 0.0
        for (v in vector) sumSq += (v * v).toDouble()
        val norm = sqrt(sumSq).toFloat()
        if (norm == 0f) return vector.copyOf()
        return FloatArray(vector.size) { vector[it] / norm }
    }
}
