package com.synapseriders.memory_recall_app.recall

import android.graphics.Bitmap
import android.os.SystemClock
import com.synapseriders.memory_recall_app.bridge.LivePreviewDto
import com.synapseriders.memory_recall_app.capture.CaptureState
import com.synapseriders.memory_recall_app.data.PersonDao
import com.synapseriders.memory_recall_app.data.PersonProfileEntity
import com.synapseriders.memory_recall_app.identity.FaceEmbeddingEngine
import com.synapseriders.memory_recall_app.identity.IdentityConfig
import com.synapseriders.memory_recall_app.identity.MatchResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * Live camera view for the Recall screen (IMPLEMENTATION_PLAN.md §14 targeting overlay).
 *
 * Each call returns the newest camera frame as a small JPEG plus what the identity engine sees in it:
 * the centered face's box, and whether it's known (with name + similarity) or unknown. Identity runs
 * at most every [IDENTIFY_INTERVAL_MS] (the image itself refreshes every call), so a fast preview
 * doesn't turn into continuous face recognition. Calling this also tells the camera to deliver
 * frames faster while the preview is on screen ([CaptureState.previewRequestedAt]).
 */
class LivePreviewProvider(
    private val personDao: PersonDao,
    private val faceEngine: FaceEmbeddingEngine,
) {
    private var lastIdentifyAt = 0L
    private var lastMatch: MatchResult? = null
    private var lastFrameW = 1
    private var lastFrameH = 1
    private var profiles: List<PersonProfileEntity> = emptyList()
    private var profilesAt = 0L

    private val lock = Mutex()

    suspend fun snapshot(maxWidth: Int): LivePreviewDto = lock.withLock { snapshotLocked(maxWidth) }

    private suspend fun snapshotLocked(maxWidth: Int): LivePreviewDto {
        val now = SystemClock.elapsedRealtime()
        CaptureState.previewRequestedAt = now
        val frame = CaptureState.latestFrame ?: return empty()

        if (now - lastIdentifyAt >= IDENTIFY_INTERVAL_MS) {
            lastIdentifyAt = now
            if (now - profilesAt >= PROFILES_TTL_MS) {
                profiles = personDao.findAll()
                profilesAt = now
            }
            lastMatch = try { faceEngine.identify(frame, profiles) } catch (_: Throwable) { null }
            lastFrameW = frame.width
            lastFrameH = frame.height
        }

        val jpeg = encode(frame, maxWidth)
        val m = lastMatch
        val box = m?.faceBox
        return LivePreviewDto(
            jpeg = jpeg.bytes,
            width = jpeg.width.toLong(),
            height = jpeg.height.toLong(),
            reticle = when {
                m == null -> "idle"
                m.isKnown -> "known"
                else -> "unknown"
            },
            personName = m?.name,
            similarity = m?.similarityScore?.toDouble(),
            faceLeft = box?.let { (it.left / lastFrameW).toDouble() },
            faceTop = box?.let { (it.top / lastFrameH).toDouble() },
            faceWidth = box?.let { (it.width / lastFrameW).toDouble() },
            faceHeight = box?.let { (it.height / lastFrameH).toDouble() },
            centerRadius = IdentityConfig.MAX_CENTER_OFFSET_FRAC.toDouble(),
        )
    }

    private class Jpeg(val bytes: ByteArray, val width: Int, val height: Int)

    private fun encode(frame: Bitmap, maxWidth: Int): Jpeg {
        val scale = min(1f, maxWidth.coerceAtLeast(64).toFloat() / frame.width)
        val w = (frame.width * scale).toInt().coerceAtLeast(1)
        val h = (frame.height * scale).toInt().coerceAtLeast(1)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(frame, w, h, true) else frame
        val out = ByteArrayOutputStream(64 * 1024)
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (scaled !== frame) scaled.recycle()
        return Jpeg(out.toByteArray(), w, h)
    }

    private fun empty() = LivePreviewDto(
        jpeg = null,
        width = 0,
        height = 0,
        reticle = "idle",
        centerRadius = IdentityConfig.MAX_CENTER_OFFSET_FRAC.toDouble(),
    )

    private companion object {
        const val IDENTIFY_INTERVAL_MS = 400L
        const val PROFILES_TTL_MS = 2_000L
        const val JPEG_QUALITY = 70
    }
}
