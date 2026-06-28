package com.synapseriders.memory_recall_app.identity

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.synapseriders.memory_recall_app.ai.FaceBox
import com.synapseriders.memory_recall_app.ai.ImagePreprocess
import com.synapseriders.memory_recall_app.ai.OnDeviceModelRunner
import com.synapseriders.memory_recall_app.ai.StageLatency
import com.synapseriders.memory_recall_app.data.EmbeddingCodec
import com.synapseriders.memory_recall_app.data.PersonProfileEntity
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Orchestrates the v4 identity pipeline (IMPLEMENTATION_PLAN.md §3.2, §4): detect → select **center**
 * face → align via 5 landmarks → CavaFace embed → dot-product match against every profile.
 *
 * Depends only on [OnDeviceModelRunner] (the CP3 `CompositeModelRunner` in production, `FakeModelRunner`
 * in tests). Returns null when no face is near the frame center (Case C). Identity uses **only**
 * `face_embedding` — never the landmarks, which are alignment-only (§3.7).
 *
 * > §24 deviation: §4 marks the geometry helpers `private`; they are `internal` here so the CP4 unit
 * > tests can assert center-selection / cosine / averaging directly. The §4 `preprocess(...)` step is
 * > not duplicated here — `QnnModelRunner.embedFace` already applies the §2.1 CavaFace preprocessing to
 * > the aligned 112×112 bitmap produced by [alignFace].
 */
class FaceEmbeddingEngine(private val runner: OnDeviceModelRunner) {

    /**
     * Full pipeline for one frame. Returns a [MatchResult] for the center face (known or unknown), or
     * null if no face is within [IdentityConfig.MAX_CENTER_OFFSET_PX] of the frame center (Case C).
     */
    fun identify(frame: Bitmap, profiles: List<PersonProfileEntity>): MatchResult? {
        val faces = runner.detectFaces(frame)
        val target = selectCenterFace(faces, frame.width, frame.height) ?: return null

        val crop = cropFace(frame, target) ?: return null
        val landmarks = runner.extractLandmarks(crop)
        val aligned = alignFace(crop, landmarks)
        val query = runner.embedFace(aligned)
        if (aligned !== crop) aligned.recycle()
        if (crop !== frame) crop.recycle()
        if (query.isEmpty()) return null

        return StageLatency.measure(StageLatency.IDENTITY_MATCH) {
            var best: PersonProfileEntity? = null
            var bestScore = -1f
            for (profile in profiles) {
                val stored = EmbeddingCodec.fromBlob(profile.faceEmbedding)
                if (stored.size != query.size) continue
                val score = cosineSimilarity(query, stored)
                if (score > bestScore) {
                    bestScore = score
                    best = profile
                }
            }
            val known = best != null && bestScore >= IdentityConfig.TAU_KNOWN
            MatchResult(
                personId = if (known) best!!.personId.toInt() else UNKNOWN_PERSON_ID,
                name = if (known) best!!.name else null,
                similarityScore = max(0f, bestScore),
                isKnown = known,
                faceBox = target,
                embedding = query,
            )
        }
    }

    /**
     * Face whose bbox center is closest to the frame center (§3.2). Faces smaller than
     * [IdentityConfig.MIN_FACE_SIZE_PX] are ignored; null if the nearest qualifying face is beyond
     * [IdentityConfig.MAX_CENTER_OFFSET_PX] (no intentional target).
     */
    internal fun selectCenterFace(faces: List<FaceBox>, frameWidth: Int, frameHeight: Int): FaceBox? {
        var bestBox: FaceBox? = null
        var bestDist = Float.MAX_VALUE
        for (face in faces) {
            if (min(face.width, face.height) < IdentityConfig.MIN_FACE_SIZE_PX) continue
            val dist = distanceToCenter(face, frameWidth, frameHeight)
            if (dist < bestDist) {
                bestDist = dist
                bestBox = face
            }
        }
        return if (bestBox != null && bestDist <= IdentityConfig.MAX_CENTER_OFFSET_PX) bestBox else null
    }

    internal fun distanceToCenter(box: FaceBox, frameWidth: Int, frameHeight: Int): Float =
        hypot(box.centerX - frameWidth / 2f, box.centerY - frameHeight / 2f)

    /**
     * Align [crop] to a 112×112 face using the 5 landmarks (§3.7): a 2-point similarity transform that
     * maps the eye centers onto the canonical ArcFace template, standardizing roll + inter-ocular
     * distance. Falls back to a plain resize when landmarks are unavailable.
     */
    fun alignFace(crop: Bitmap, landmarks: FloatArray): Bitmap {
        val size = ImagePreprocess.CAVAFACE_SIZE
        if (landmarks.size < 4) {
            return Bitmap.createScaledBitmap(crop, size, size, true)
        }
        val srcEyes = floatArrayOf(landmarks[0], landmarks[1], landmarks[2], landmarks[3])
        val matrix = Matrix().apply { setPolyToPoly(srcEyes, 0, DST_EYES, 0, 2) }
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(crop, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** Cosine similarity of two L2-normalized vectors = dot product (§4). */
    internal fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        val n = min(a.size, b.size)
        for (i in 0 until n) dot += a[i] * b[i]
        return dot
    }

    /** Average N embeddings then L2-normalize — used by enrollment (CP8). */
    fun averageEmbeddings(embeddings: List<FloatArray>): FloatArray {
        if (embeddings.isEmpty()) return FloatArray(0)
        val dim = embeddings.first().size
        val sum = FloatArray(dim)
        for (e in embeddings) {
            if (e.size != dim) continue
            for (i in 0 until dim) sum[i] += e[i]
        }
        for (i in 0 until dim) sum[i] /= embeddings.size
        return l2Normalize(sum)
    }

    internal fun l2Normalize(v: FloatArray): FloatArray = EmbeddingCodec.l2Normalize(v)

    private fun cropFace(frame: Bitmap, box: FaceBox): Bitmap? {
        val x = box.left.toInt().coerceIn(0, frame.width - 1)
        val y = box.top.toInt().coerceIn(0, frame.height - 1)
        val w = box.width.toInt().coerceAtLeast(1).coerceAtMost(frame.width - x)
        val h = box.height.toInt().coerceAtLeast(1).coerceAtMost(frame.height - y)
        return try {
            Bitmap.createBitmap(frame, x, y, w, h)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private companion object {
        const val UNKNOWN_PERSON_ID = -1
        /** Canonical ArcFace 112×112 eye positions: left eye, right eye. */
        val DST_EYES = floatArrayOf(38.2946f, 51.6963f, 73.5318f, 51.5014f)
    }
}

/**
 * Outcome of [FaceEmbeddingEngine.identify] for the center face (§4).
 * [personId] is -1 and [name] null for an unknown face (Case B).
 */
data class MatchResult(
    val personId: Int,
    val name: String?,
    val similarityScore: Float,
    val isKnown: Boolean,
    val faceBox: FaceBox,
    /** The L2-normalized query embedding — persisted as the new profile for an unknown face (§10.B). */
    val embedding: FloatArray = FloatArray(0),
)
