package com.synapseriders.memory_recall_app.ai

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * ExecuTorch face **detection** + **landmark** runner (IMPLEMENTATION_PLAN.md §2, §8.2, §22 CP3).
 *
 * Owns the two `.pte` models via [ExecuTorchBackend]:
 *   - `mediapipe_face_detection.pte`   → [detectFaces]    (bbox + confidence)
 *   - `mediapipe_face_landmark_detector.pte` → [extractLandmarks] (5 alignment points, 10 floats)
 *
 * It does **not** implement [OnDeviceModelRunner] directly — [CompositeModelRunner] composes it with
 * [QnnModelRunner] and [GenieLlmRunner]. All methods degrade gracefully (empty result) when the AAR
 * or model file is missing; warm-up is off the UI thread at the call site (§18).
 *
 * ⚠️ **Decode constants are unverified** — the precise output tensor layout of these MediaPipe exports
 * is not documented (cf. the CavaFace I/O caveat, §2.1). [DetectCfg] / [LandmarkCfg] centralize every
 * model-specific assumption; confirm them against the real models on-device and tune in one place
 * (tracked in §24).
 */
class ExecuTorchModelRunner(private val assets: ModelAssets) {

    @Volatile private var detector: ExecuTorchBackend? = null
    @Volatile private var landmarker: ExecuTorchBackend? = null

    /** Load both `.pte` modules once. Safe to call repeatedly; never throws. */
    fun warmUp() {
        if (!ExecuTorchBackend.isAvailable) {
            Log.i(TAG, "ExecuTorch AAR not present — detection/landmark disabled (fallback)")
            return
        }
        if (detector == null && assets.hasFaceDetection()) {
            detector = ExecuTorchBackend.load(assets.faceDetection.absolutePath)
            Log.i(TAG, "face_detection loaded=${detector != null}")
        }
        if (landmarker == null && assets.hasFaceLandmark()) {
            landmarker = ExecuTorchBackend.load(assets.faceLandmark.absolutePath)
            Log.i(TAG, "face_landmark loaded=${landmarker != null}")
        }
    }

    /** Detect faces in [frame]; bbox/confidence in source-frame pixels. Empty when unavailable. */
    fun detectFaces(frame: Bitmap): List<FaceBox> {
        val model = detector ?: run { warmUp(); detector } ?: return emptyList()
        return StageLatency.measure(StageLatency.FACE_DETECTION) {
            try {
                val input = ImagePreprocess.toChwFloat(
                    frame, DetectCfg.INPUT, DetectCfg.INPUT, mean = 127.5f, scale = 1f / 127.5f,
                )
                val outputs = model.forward(input, ImagePreprocess.nchwShape(DetectCfg.INPUT, DetectCfg.INPUT))
                decodeDetections(outputs, frame.width, frame.height)
            } catch (t: Throwable) {
                Log.w(TAG, "detectFaces failed", t)
                emptyList()
            }
        }
    }

    /** 5 alignment key points (10 floats `[x0,y0,…]`) in [faceCrop] pixel coords. Empty on failure. */
    fun extractLandmarks(faceCrop: Bitmap): FloatArray {
        val model = landmarker ?: run { warmUp(); landmarker } ?: return FloatArray(0)
        return StageLatency.measure(StageLatency.FACE_ALIGNMENT) {
            try {
                val input = ImagePreprocess.toChwFloat(
                    faceCrop, LandmarkCfg.INPUT, LandmarkCfg.INPUT, mean = 0f, scale = 1f / 255f,
                )
                val outputs = model.forward(input, ImagePreprocess.nchwShape(LandmarkCfg.INPUT, LandmarkCfg.INPUT))
                decodeLandmarks(outputs, faceCrop.width, faceCrop.height)
            } catch (t: Throwable) {
                Log.w(TAG, "extractLandmarks failed", t)
                FloatArray(0)
            }
        }
    }

    fun close() {
        detector?.close(); detector = null
        landmarker?.close(); landmarker = null
    }

    // --- Decode (model-specific; constants flagged, §24) ----------------------------------------

    /**
     * BlazeFace-style SSD decode: expects a scores tensor and a 16-wide box tensor over [DetectCfg]
     * anchors. Applies sigmoid + threshold + NMS and maps the box to source-frame pixels. Returns
     * empty (not garbage) if the output shape doesn't match the assumed layout.
     */
    private fun decodeDetections(outputs: List<FloatArray>, srcW: Int, srcH: Int): List<FaceBox> {
        if (outputs.size < 2) return emptyList()
        // Heuristically pick the 1-wide scores tensor and the (≥4)-wide boxes tensor.
        val scores = outputs.minByOrNull { it.size } ?: return emptyList()
        val boxes = outputs.maxByOrNull { it.size } ?: return emptyList()
        val n = scores.size
        if (n == 0 || boxes.size % n != 0) return emptyList()
        val stride = boxes.size / n
        if (stride < 4) return emptyList()

        val anchors = DetectCfg.anchors(n)
        val candidates = ArrayList<FaceBox>()
        for (i in 0 until n) {
            val score = sigmoid(scores[i])
            if (score < DetectCfg.MIN_SCORE) continue
            val a = anchors?.get(i)
            val base = i * stride
            // BlazeFace boxes are (dx, dy, w, h) relative to the anchor, in input-normalized units.
            val cx = (boxes[base] / DetectCfg.INPUT + (a?.cx ?: 0f))
            val cy = (boxes[base + 1] / DetectCfg.INPUT + (a?.cy ?: 0f))
            val w = boxes[base + 2] / DetectCfg.INPUT
            val h = boxes[base + 3] / DetectCfg.INPUT
            val left = (cx - w / 2f) * srcW
            val top = (cy - h / 2f) * srcH
            candidates += FaceBox(left, top, w * srcW, h * srcH, score)
        }
        return nms(candidates, DetectCfg.NMS_IOU)
    }

    /**
     * MediaPipe FaceMesh outputs 468 (x,y,z) landmarks in input pixels. We keep only the 5 alignment
     * points (eye centers, nose tip, mouth corners) at [LandmarkCfg.MESH_INDICES], rescaled to the
     * crop. If the model is the 5-point detector variant, the first 10 floats are used directly.
     */
    private fun decodeLandmarks(outputs: List<FloatArray>, cropW: Int, cropH: Int): FloatArray {
        val raw = outputs.firstOrNull { it.isNotEmpty() } ?: return FloatArray(0)
        val sx = cropW.toFloat() / LandmarkCfg.INPUT
        val sy = cropH.toFloat() / LandmarkCfg.INPUT
        // FaceMesh: 468*3 floats → select 5 points.
        if (raw.size >= 468 * 3) {
            val out = FloatArray(10)
            LandmarkCfg.MESH_INDICES.forEachIndexed { k, idx ->
                out[k * 2] = raw[idx * 3] * sx
                out[k * 2 + 1] = raw[idx * 3 + 1] * sy
            }
            return out
        }
        // 5-point variant: 10 floats, normalized [0,1] or input-pixel — assume input-pixel.
        if (raw.size >= 10) {
            return FloatArray(10) { i -> raw[i] * (if (i % 2 == 0) sx else sy) }
        }
        return FloatArray(0)
    }

    // --- Helpers --------------------------------------------------------------------------------

    private fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))

    private fun nms(boxes: List<FaceBox>, iouThresh: Float): List<FaceBox> {
        val sorted = boxes.sortedByDescending { it.confidence }.toMutableList()
        val kept = ArrayList<FaceBox>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept += best
            sorted.removeAll { iou(best, it) > iouThresh }
        }
        return kept
    }

    private fun iou(a: FaceBox, b: FaceBox): Float {
        val x1 = max(a.left, b.left)
        val y1 = max(a.top, b.top)
        val x2 = min(a.left + a.width, b.left + b.width)
        val y2 = min(a.top + a.height, b.top + b.height)
        val inter = max(0f, x2 - x1) * max(0f, y2 - y1)
        val union = a.width * a.height + b.width * b.height - inter
        return if (union <= 0f) 0f else inter / union
    }

    /** Detection model assumptions — VERIFY on-device (§24). */
    private object DetectCfg {
        const val INPUT = 256          // BlazeFace back-model input; confirm against the .pte
        const val MIN_SCORE = 0.6f
        const val NMS_IOU = 0.3f
        /** Anchor grid; null → boxes treated as already absolute-normalized (no anchor offset). */
        fun anchors(@Suppress("UNUSED_PARAMETER") count: Int): Array<Anchor>? = null
    }

    private class Anchor(val cx: Float, val cy: Float)

    /** Landmark model assumptions — VERIFY on-device (§24). */
    private object LandmarkCfg {
        const val INPUT = 192          // FaceMesh input; confirm against the .pte
        /** FaceMesh indices for L-eye, R-eye, nose tip, L-mouth, R-mouth. */
        val MESH_INDICES = intArrayOf(33, 263, 1, 61, 291)
    }

    private companion object {
        const val TAG = "ExecuTorchRunner"
    }
}
