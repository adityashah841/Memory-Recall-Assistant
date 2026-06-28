package com.synapseriders.memory_recall_app.enrollment

import android.graphics.Bitmap
import android.util.Log
import com.synapseriders.memory_recall_app.data.EmbeddingCodec
import com.synapseriders.memory_recall_app.data.PersonDao
import com.synapseriders.memory_recall_app.data.PersonProfileEntity
import com.synapseriders.memory_recall_app.identity.FaceEmbeddingEngine
import com.synapseriders.memory_recall_app.identity.IdentityConfig
import kotlinx.coroutines.delay

/** Outcome of a proactive enrollment. */
data class EnrollResult(val success: Boolean, val personId: Long, val samples: Int, val message: String?)

/**
 * Proactive enrollment (IMPLEMENTATION_PLAN.md §3.6, §22 CP8): capture
 * [IdentityConfig.ENROLLMENT_SAMPLES] centered-face embeddings from the live camera, average +
 * L2-normalize them (via [FaceEmbeddingEngine.averageEmbeddings]), and store a new named profile so the
 * person is recognized as Case A afterward.
 *
 * Uses the same center-crop selection as recognition (`identify` returns null when no centered face),
 * so only the intended subject is enrolled. Timing is injectable for tests.
 */
class PersonEnrollmentManager(
    private val personDao: PersonDao,
    private val faceEngine: FaceEmbeddingEngine,
    private val frameProvider: () -> Bitmap?,
    private val sampleIntervalMs: Long = 250,
    private val maxAttempts: Int = 40,
) {

    suspend fun enroll(name: String, contextNotes: String?): EnrollResult {
        val target = IdentityConfig.ENROLLMENT_SAMPLES
        val samples = ArrayList<FloatArray>(target)
        var attempts = 0
        while (samples.size < target && attempts < maxAttempts) {
            attempts++
            val frame = frameProvider()
            if (frame != null) {
                // profiles empty: we only need the centered face's embedding, not a match.
                val embedding = faceEngine.identify(frame, emptyList())?.embedding
                if (embedding != null && embedding.isNotEmpty()) samples.add(embedding)
            }
            if (samples.size < target) delay(sampleIntervalMs)
        }

        if (samples.size < target) {
            Log.i(TAG, "enroll incomplete: ${samples.size}/$target centered samples")
            return EnrollResult(
                success = false,
                personId = -1L,
                samples = samples.size,
                message = "Couldn't capture $target centered frames — keep the face in the center.",
            )
        }

        val averaged = faceEngine.averageEmbeddings(samples)
        val now = System.currentTimeMillis()
        val personId = personDao.insert(
            PersonProfileEntity(
                name = name,
                faceEmbedding = EmbeddingCodec.toBlob(averaged),
                contextNotes = contextNotes,
                createdAt = now,
                updatedAt = now,
            ),
        )
        Log.i(TAG, "enrolled '$name' as #$personId from ${samples.size} samples")
        return EnrollResult(true, personId, samples.size, "Enrolled $name")
    }

    private companion object {
        const val TAG = "EnrollmentManager"
    }
}
