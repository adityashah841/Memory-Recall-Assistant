package com.synapseriders.memory_recall_app.ai

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.util.concurrent.TimeUnit

/**
 * Face detection + 5 alignment key points via Google ML Kit (bundled model — fully on-device, no
 * network). Replaces the two MediaPipe `.pte` models on non-Qualcomm devices: one ML Kit call returns
 * both the bounding boxes and the eye/nose/mouth landmarks.
 *
 * Blocking API (waits on the ML Kit task) — call off the main thread, like every runner (§18).
 */
class MlKitFaceDetector {

    private val detector: FaceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(MIN_FACE_RATIO)
                .build(),
        )
    }

    /** Faces in [frame] (frame pixels), each carrying its 5 landmarks when ML Kit found them. */
    fun detect(frame: Bitmap): List<FaceBox> = StageLatency.measure(StageLatency.FACE_DETECTION) {
        try {
            val faces = Tasks.await(
                detector.process(InputImage.fromBitmap(frame, 0)),
                TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            )
            faces.map { it.toFaceBox() }
        } catch (t: Throwable) {
            Log.w(TAG, "face detection failed", t)
            emptyList()
        }
    }

    fun warmUp() {
        detector // force client creation
    }

    fun close() {
        try { detector.close() } catch (_: Throwable) {}
    }

    private fun Face.toFaceBox(): FaceBox {
        val b = boundingBox
        return FaceBox(
            left = b.left.toFloat(),
            top = b.top.toFloat(),
            width = b.width().toFloat(),
            height = b.height().toFloat(),
            confidence = 1f, // ML Kit doesn't expose a detection score
            landmarks = landmarks5(),
        )
    }

    /**
     * `[x0,y0,…,x4,y4]` = image-left eye, image-right eye, nose, image-left mouth, image-right mouth.
     * ML Kit names landmarks from the subject's point of view (their LEFT_EYE appears on the image's
     * right for the back camera), so pairs are ordered by x instead of by name. Null if any is missing.
     */
    private fun Face.landmarks5(): FloatArray? {
        val eyeA = getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return null
        val eyeB = getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return null
        val nose = getLandmark(FaceLandmark.NOSE_BASE)?.position ?: return null
        val mouthA = getLandmark(FaceLandmark.MOUTH_LEFT)?.position ?: return null
        val mouthB = getLandmark(FaceLandmark.MOUTH_RIGHT)?.position ?: return null
        val (leftEye, rightEye) = if (eyeA.x <= eyeB.x) eyeA to eyeB else eyeB to eyeA
        val (leftMouth, rightMouth) = if (mouthA.x <= mouthB.x) mouthA to mouthB else mouthB to mouthA
        return floatArrayOf(
            leftEye.x, leftEye.y,
            rightEye.x, rightEye.y,
            nose.x, nose.y,
            leftMouth.x, leftMouth.y,
            rightMouth.x, rightMouth.y,
        )
    }

    private companion object {
        const val TAG = "MlKitFaceDetector"
        const val TIMEOUT_MS = 2_000L
        /** Smallest head width ML Kit reports, as a fraction of image width (the engine filters too). */
        const val MIN_FACE_RATIO = 0.1f
    }
}
