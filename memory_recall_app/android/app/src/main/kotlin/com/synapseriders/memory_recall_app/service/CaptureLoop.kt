package com.synapseriders.memory_recall_app.service

import android.util.Log
import com.synapseriders.memory_recall_app.capture.LocationFix
import com.synapseriders.memory_recall_app.data.CaptureRecord
import com.synapseriders.memory_recall_app.data.CaptureSink
import com.synapseriders.memory_recall_app.data.EmbeddingCodec
import com.synapseriders.memory_recall_app.data.PersonProfileEntity
import com.synapseriders.memory_recall_app.identity.MatchResult

/** Result of one capture tick (used for logging + tests). */
enum class TickOutcome { NO_FRAME, SKIPPED, KNOWN, UNKNOWN, NO_PERSON }

/**
 * One periodic capture tick (IMPLEMENTATION_PLAN.md §9.1, §10): grab the current frame, identify the
 * center face, gather transcript + location, apply the §10.4 skip conditions, then insert Case A/B/C.
 *
 * Generic over the frame type [F] (Bitmap in production) so the decision logic is unit-tested on the
 * JVM without a camera, mic, Room, or an Android Bitmap. All collaborators are injected.
 */
class CaptureLoop<F>(
    private val identify: (F, List<PersonProfileEntity>) -> MatchResult?,
    private val sink: CaptureSink,
    private val sceneChanged: (F) -> Boolean,
    private val profilesProvider: suspend () -> List<PersonProfileEntity>,
    private val frameProvider: () -> F?,
    private val transcriptProvider: () -> String?,
    private val locationProvider: () -> LocationFix?,
    private val thumbnailSaver: (F) -> String,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** Run one tick; returns the outcome. Never throws. */
    suspend fun tick(): TickOutcome {
        val frame = frameProvider() ?: return TickOutcome.NO_FRAME
        return try {
            val match = identify(frame, profilesProvider())
            val transcript = transcriptProvider()?.takeIf { it.isNotBlank() }
            val facePresent = match != null
            val changed = sceneChanged(frame)

            // §10.4: persist only if a face is present, OR there is a transcript, OR the scene changed.
            if (!facePresent && transcript == null && !changed) return TickOutcome.SKIPPED

            val loc = locationProvider()
            val rec = CaptureRecord(
                timestamp = clock(),
                locationLabel = loc?.label,
                latitude = loc?.latitude,
                longitude = loc?.longitude,
                frameThumbPath = thumbnailSaver(frame),
                transcript = transcript,
            )
            when {
                match == null -> { sink.recordNoPerson(rec); TickOutcome.NO_PERSON }            // Case C
                match.isKnown -> { sink.recordKnown(match.personId.toLong(), rec); TickOutcome.KNOWN } // Case A
                else -> {                                                                        // Case B
                    sink.recordUnknown(EmbeddingCodec.toBlob(match.embedding), rec)
                    TickOutcome.UNKNOWN
                }
            }.also { Log.d(TAG, "tick → $it") }
        } catch (t: Throwable) {
            Log.w(TAG, "tick failed", t)
            TickOutcome.SKIPPED
        }
    }

    private companion object {
        const val TAG = "CaptureLoop"
    }
}
