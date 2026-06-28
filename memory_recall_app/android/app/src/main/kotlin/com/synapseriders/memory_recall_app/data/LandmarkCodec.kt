package com.synapseriders.memory_recall_app.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Converts landmark vectors to/from the on-disk representation: generic `float32[]` <-> little-endian
 * blob, with L2 normalization (IMPLEMENTATION_PLAN.md Section 5, v2 landmark schema — Checkpoint 2.5).
 *
 * The only vector stored is `person_profile.landmark_vector` (936-d = 468 face-mesh points x (x, y)).
 * It is L2-normalized at write time so geometric similarity reduces to a dot product during the
 * landmark scan (Section 7). Privacy: the v2 schema stores landmark *geometry* only — never face
 * images or learned embeddings.
 */
object LandmarkCodec {

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
