package com.synapseriders.memory_recall_app.ai

import android.util.Log

/**
 * JNI surface for the Qualcomm runtimes that have no JVM API: the raw **QNN context-binary** models
 * (CavaFace, Whisper) and the **QAIRT Genie** LLM (IMPLEMENTATION_PLAN.md §2, §8.2).
 *
 * The native library (`libsynapse_inference.so`, built from `app/src/main/cpp/` against the QAIRT
 * 2.45 SDK) links the QNN/Genie `.so` runtime libs shipped in `jniLibs/arm64-v8a/`. Until the SDK is
 * wired and the library is built, [loaded] is false and every caller degrades gracefully — the
 * `external` declarations still compile, so the project builds with zero Qualcomm artifacts present
 * (§22 CP3 "no crash"; §24 deviation note).
 *
 * Handles are opaque native pointers (0 == failure/none).
 */
object NativeInference {

    /** True when libsynapse_inference.so loaded — i.e. the QNN/Genie native path is available. */
    val loaded: Boolean = try {
        System.loadLibrary("synapse_inference")
        true
    } catch (t: Throwable) {
        Log.i("NativeInference", "native QNN/Genie lib not present — CavaFace/Whisper/LLM will fall back")
        false
    }

    // --- QNN backend lifecycle (call once at warm-up) ---
    /** Initialize the QNN HTP backend; [nativeLibDir] is the app's jniLibs dir holding libQnn*.so. */
    external fun qnnInit(nativeLibDir: String): Boolean

    // --- CavaFace (cavaface.bin) ---
    /** Load the CavaFace context binary; returns an opaque handle (0 on failure). */
    external fun cavaFaceLoad(modelPath: String): Long
    /** Run CavaFace on a `float32[1,3,112,112]` input; returns `float32[512]` (null on failure). */
    external fun cavaFaceEmbed(handle: Long, input: FloatArray): FloatArray?
    external fun cavaFaceFree(handle: Long)

    // --- Whisper (encoder.bin + decoder.bin) ---
    /** Load both Whisper context binaries; returns an opaque handle (0 on failure). */
    external fun whisperLoad(encoderPath: String, decoderPath: String): Long
    /** Transcribe a log-mel `float32[1,80,3000]` feature tensor; returns text ("" on failure). */
    external fun whisperTranscribe(handle: Long, logMel: FloatArray): String
    external fun whisperFree(handle: Long)

    // --- Genie LLM (3-part bundle) ---
    /** Create a Genie dialog from genie_config.json's directory; returns a handle (0 on failure). */
    external fun genieCreate(bundleDir: String, configFileName: String): Long
    /** Generate a completion for [prompt] (already in Llama chat format); "" on failure. */
    external fun genieGenerate(handle: Long, prompt: String, maxTokens: Int): String
    external fun genieFree(handle: Long)
}
