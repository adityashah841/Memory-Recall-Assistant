package com.synapseriders.memory_recall_app.ai

import android.graphics.Bitmap

/**
 * On-device inference contract (IMPLEMENTATION_PLAN.md §8.3).
 *
 * v4.1 splits the implementation across three backend runners by runtime — see [ExecuTorchModelRunner]
 * (face detection + landmark, ExecuTorch `.pte`), [QnnModelRunner] (CavaFace + Whisper, QNN context
 * binaries), and [GenieLlmRunner] (Llama 3.2 1B, QAIRT Genie). [CompositeModelRunner] fans these out
 * behind this single interface so callers (e.g. `FaceEmbeddingEngine`, §4) depend only on the
 * contract. [FakeModelRunner] is the deterministic test/CI stand-in.
 *
 * **Hard rules (§18, §22 CP3):** every method must run off the main thread at the call site, warm-up
 * happens once via [warmUp], and a missing model or failed load must degrade gracefully — never throw.
 */
interface OnDeviceModelRunner {

    /** All faces in [frame] with bbox + confidence (mediapipe_face_detection.pte). Empty if none. */
    fun detectFaces(frame: Bitmap): List<FaceBox>

    /** 5 alignment key points as 10 floats `[x0,y0,…,x4,y4]` (mediapipe_face_landmark_detector.pte). */
    fun extractLandmarks(faceCrop: Bitmap): FloatArray

    /** CavaFace identity embedding: `float32[512]`, L2-normalized (cavaface.bin). */
    fun embedFace(alignedFace: Bitmap): FloatArray

    /** Whisper ASR transcript for the PCM16 window; "" when unavailable. */
    fun transcribe(pcmAudio: ShortArray, sampleRate: Int): String

    /** LLM completion (Llama 3.2 1B via Genie); "" when unavailable. */
    fun generate(prompt: String, maxTokens: Int = 256): String

    /** Preload/compile every available model off the UI thread. Idempotent; never throws. */
    fun warmUp()
}

/**
 * Axis-aligned face bounding box in source-frame pixels (§8.3).
 *
 * [centerX]/[centerY] feed the center-crop selection in `FaceEmbeddingEngine.selectCenterFace` (§3.2).
 */
data class FaceBox(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val confidence: Float,
) {
    val centerX get() = left + width / 2f
    val centerY get() = top + height / 2f
}
