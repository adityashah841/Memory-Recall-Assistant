package com.synapseriders.memory_recall_app.ai

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Resolves on-device model file paths (IMPLEMENTATION_PLAN.md §16, option 2).
 *
 * Model weights are **not** bundled in the APK — they are `adb push`ed to the app's external files
 * dir (`/sdcard/Android/data/<pkg>/files/models/`, see `scripts/push_models.sh`) and loaded by path.
 * This class only locates them and reports presence so each runner can degrade gracefully when a model
 * is missing (§22 CP3: "no crash when any model is missing").
 */
class ModelAssets(context: Context) {

    /** Root: <externalFilesDir>/models — matches the §16 layout the push script writes to. */
    val root: File = File(context.getExternalFilesDir(null), "models")

    // --- ExecuTorch (.pte) ---
    val faceDetection: File get() = File(root, "mediapipe_face_detection.pte")
    val faceLandmark: File get() = File(root, "mediapipe_face_landmark_detector.pte")

    // --- QNN context binaries (.bin) ---
    val cavaFace: File get() = File(root, "cavaface.bin")
    val whisperEncoder: File get() = File(root, "whisper/encoder.bin")
    val whisperDecoder: File get() = File(root, "whisper/decoder.bin")
    val whisperMetadata: File get() = File(root, "whisper/metadata.json")

    // --- QAIRT Genie bundle ---
    val genieDir: File get() = File(root, "llama_genie")
    val genieConfig: File get() = File(genieDir, "genie_config.json")
    val genieTokenizer: File get() = File(genieDir, "tokenizer.json")
    val genieParts: List<File>
        get() = (1..3).map { File(genieDir, "part${it}_of_3.bin") }

    fun hasFaceDetection() = faceDetection.isFile
    fun hasFaceLandmark() = faceLandmark.isFile
    fun hasCavaFace() = cavaFace.isFile
    fun hasWhisper() = whisperEncoder.isFile && whisperDecoder.isFile
    fun hasGenie() = genieConfig.isFile && genieTokenizer.isFile && genieParts.all { it.isFile }

    /** Human-readable presence report for startup logging / the debug screen. */
    fun report(): String = buildString {
        append("models @ ${root.absolutePath}\n")
        append("  face_detection : ${present(hasFaceDetection())}\n")
        append("  face_landmark  : ${present(hasFaceLandmark())}\n")
        append("  cavaface       : ${present(hasCavaFace())}\n")
        append("  whisper        : ${present(hasWhisper())}\n")
        append("  llama_genie    : ${present(hasGenie())}")
    }

    fun logReport() = Log.i(TAG, report())

    private fun present(ok: Boolean) = if (ok) "present" else "MISSING"

    private companion object {
        const val TAG = "ModelAssets"
    }
}
