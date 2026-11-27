package com.synapseriders.memory_recall_app.identity

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import com.synapseriders.memory_recall_app.ai.FaceBox
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
     * null if no face is near the frame center (Case C; see [IdentityConfig.MAX_CENTER_OFFSET_FRAC]).
     */
    fun identify(frame: Bitmap, profiles: List<PersonProfileEntity>): MatchResult? {
        val faces = runner.detectFaces(frame)
        val target = selectCenterFace(faces, frame.width, frame.height) ?: return null

        val crop = cropFace(frame, target) ?: return null
        // Prefer key points the detector already produced (in frame coords → shift into the crop);
        // otherwise run the separate landmark model.
        val landmarks = target.landmarks?.let { lm ->
            FloatArray(lm.size) { i -> if (i % 2 == 0) lm[i] - crop.originX else lm[i] - crop.originY }
        } ?: runner.extractLandmarks(crop.bitmap)
        val aligned = alignFace(crop.bitmap, landmarks)
        val query = runner.embedFace(aligned)
        if (aligned !== crop.bitmap) aligned.recycle()
        if (crop.bitmap !== frame) crop.bitmap.recycle()
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
            val known = best != null && bestScore >= runner.matchThreshold
            Log.d(TAG, "center face: best=${best?.name ?: best?.personId} score=${"%.3f".format(bestScore)} " +
                "threshold=${runner.matchThreshold} known=$known (${runner.backendName})")
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
     * [IdentityConfig.MIN_FACE_SIZE_FRAC] of the frame's shorter side are ignored; null if the nearest
     * qualifying face is beyond [IdentityConfig.MAX_CENTER_OFFSET_FRAC] of it (no intentional target).
     */
    internal fun selectCenterFace(faces: List<FaceBox>, frameWidth: Int, frameHeight: Int): FaceBox? {
        val shortSide = min(frameWidth, frameHeight).toFloat()
        val minFace = IdentityConfig.MIN_FACE_SIZE_FRAC * shortSide
        val maxOffset = IdentityConfig.MAX_CENTER_OFFSET_FRAC * shortSide
        var bestBox: FaceBox? = null
        var bestDist = Float.MAX_VALUE
        for (face in faces) {
            if (min(face.width, face.height) < minFace) continue
            val dist = distanceToCenter(face, frameWidth, frameHeight)
            if (dist < bestDist) {
                bestDist = dist
                bestBox = face
            }
        }
        return if (bestBox != null && bestDist <= maxOffset) bestBox else null
    }

    internal fun distanceToCenter(box: FaceBox, frameWidth: Int, frameHeight: Int): Float =
        hypot(box.centerX - frameWidth / 2f, box.centerY - frameHeight / 2f)

    /**
     * Align [crop] for the backend's embedder ([OnDeviceModelRunner.faceAlignment], §3.7). With an eye
     * template (ArcFace/CavaFace): a 2-point similarity transform mapping the eye centers onto the
     * template, standardizing roll + inter-ocular distance. Without one (FaceNet), or when landmarks
     * are unavailable: a plain resize of the bbox crop.
     */
    fun alignFace(crop: Bitmap, landmarks: FloatArray): Bitmap {
        val spec = runner.faceAlignment
        val size = spec.size
        val template = spec.eyeTemplate
        if (template == null || landmarks.size < 4) {
            return Bitmap.createScaledBitmap(crop, size, size, true)
        }
        val srcEyes = floatArrayOf(landmarks[0], landmarks[1], landmarks[2], landmarks[3])
        val matrix = Matrix().apply { setPolyToPoly(srcEyes, 0, template, 0, 2) }
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

    /** A face crop plus where it sits in the frame (to shift frame-space landmarks into it). */
    private class Crop(val bitmap: Bitmap, val originX: Int, val originY: Int)

    private fun cropFace(frame: Bitmap, box: FaceBox): Crop? {
        val x = box.left.toInt().coerceIn(0, frame.width - 1)
        val y = box.top.toInt().coerceIn(0, frame.height - 1)
        val w = box.width.toInt().coerceAtLeast(1).coerceAtMost(frame.width - x)
        val h = box.height.toInt().coerceAtLeast(1).coerceAtMost(frame.height - y)
        return try {
            Crop(Bitmap.createBitmap(frame, x, y, w, h), x, y)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private companion object {
        const val TAG = "FaceEmbeddingEngine"
        const val UNKNOWN_PERSON_ID = -1
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
