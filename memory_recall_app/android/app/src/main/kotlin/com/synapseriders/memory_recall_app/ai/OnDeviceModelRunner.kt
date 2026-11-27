package com.synapseriders.memory_recall_app.ai

import android.graphics.Bitmap
import com.synapseriders.memory_recall_app.identity.IdentityConfig

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

    /**
     * Chat-style completion from a [system] instruction and a [user] message; "" when unavailable.
     * Callers use this instead of hand-formatting model-specific prompts. Default: wrap in the
     * Llama 3 chat template (§7.1/§7.2) and call [generate] — right for the Genie backend; backends
     * with a native chat API (e.g. Gemini Nano) override it.
     */
    fun generateChat(system: String, user: String, maxTokens: Int = 256): String =
        generate(llama3Chat(system, user), maxTokens)

    /** Preload/compile every available model off the UI thread. Idempotent; never throws. */
    fun warmUp()

    /** How `FaceEmbeddingEngine` should crop/align a face before [embedFace] for this backend. */
    val faceAlignment: FaceAlignment get() = FaceAlignment.ARCFACE_112

    /** Cosine-similarity threshold for a "known" match with this backend's embedder. */
    val matchThreshold: Float get() = IdentityConfig.TAU_KNOWN

    /** Short human-readable backend name for logs and the debug screen. */
    val backendName: String get() = javaClass.simpleName
}

/** Llama 3 instruct chat template (IMPLEMENTATION_PLAN.md §7.1/§7.2). */
fun llama3Chat(system: String, user: String): String = buildString {
    append("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n")
    append(system.trim()).append('\n')
    append("<|eot_id|><|start_header_id|>user<|end_header_id|>\n")
    append(user.trim()).append('\n')
    append("<|eot_id|><|start_header_id|>assistant<|end_header_id|>")
}

/**
 * Face crop/alignment spec for an embedder. [eyeTemplate] = target (x,y) of the image-left and
 * image-right eye in the [size]×[size] output; null means a plain bbox crop resized to [size]
 * (what FaceNet was trained on).
 */
data class FaceAlignment(val size: Int, val eyeTemplate: FloatArray?) {
    companion object {
        /** ArcFace / CavaFace canonical 112×112 eye positions (§3.7). */
        val ARCFACE_112 = FaceAlignment(112, floatArrayOf(38.2946f, 51.6963f, 73.5318f, 51.5014f))

        /** FaceNet: bbox crop resized to 160×160, no eye warp. */
        val FACENET_160 = FaceAlignment(160, null)
    }
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
    /**
     * Optional 5 key points in **frame** pixels `[x0,y0,…,x4,y4]` (image-left eye, image-right eye,
     * nose, image-left mouth, image-right mouth) when the detector provides them (e.g. ML Kit), so
     * no separate landmark model call is needed.
     */
    val landmarks: FloatArray? = null,
) {
    val centerX get() = left + width / 2f
    val centerY get() = top + height / 2f
}
