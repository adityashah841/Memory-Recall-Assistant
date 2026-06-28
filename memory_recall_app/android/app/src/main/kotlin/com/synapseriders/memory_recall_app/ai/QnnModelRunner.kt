package com.synapseriders.memory_recall_app.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.synapseriders.memory_recall_app.data.EmbeddingCodec

/**
 * QNN context-binary runner (IMPLEMENTATION_PLAN.md §2, §8.2): CavaFace face embedding + Whisper ASR.
 *
 * Inference runs through [NativeInference] (JNI → QAIRT 2.45). CavaFace preprocessing is exact per
 * §2.1 (112×112, CHW, [-1,1]); the 512-d result is L2-normalized via [EmbeddingCodec] so identity
 * matching reduces to a dot product. Whisper's full log-mel + decode loop is **CP5**; here the runner
 * loads the binaries and exposes [transcribe] plumbing that falls back to "" until CP5.
 *
 * Not an [OnDeviceModelRunner] itself — composed by [CompositeModelRunner]. Degrades gracefully when
 * the native lib or a model file is missing (§22 CP3).
 */
class QnnModelRunner(
    context: Context,
    private val assets: ModelAssets,
) {
    private val nativeLibDir: String = context.applicationInfo.nativeLibraryDir

    @Volatile private var qnnReady = false
    @Volatile private var cavaHandle = 0L
    @Volatile private var whisperHandle = 0L

    /** Load the QNN backend + CavaFace/Whisper context binaries once. Never throws. */
    fun warmUp() {
        if (!NativeInference.loaded) {
            Log.i(TAG, "native QNN lib absent — CavaFace/Whisper disabled (fallback)")
            return
        }
        if (!qnnReady) {
            qnnReady = try {
                NativeInference.qnnInit(nativeLibDir)
            } catch (t: Throwable) {
                Log.w(TAG, "qnnInit failed", t); false
            }
            if (!qnnReady) return
        }
        if (cavaHandle == 0L && assets.hasCavaFace()) {
            cavaHandle = safe { NativeInference.cavaFaceLoad(assets.cavaFace.absolutePath) }
            Log.i(TAG, "cavaface loaded=${cavaHandle != 0L}")
        }
        if (whisperHandle == 0L && assets.hasWhisper()) {
            whisperHandle = safe {
                NativeInference.whisperLoad(assets.whisperEncoder.absolutePath, assets.whisperDecoder.absolutePath)
            }
            Log.i(TAG, "whisper loaded=${whisperHandle != 0L}")
        }
    }

    /** CavaFace identity embedding: `float32[512]`, L2-normalized. Empty when unavailable. */
    fun embedFace(alignedFace: Bitmap): FloatArray {
        if (cavaHandle == 0L) { warmUp(); if (cavaHandle == 0L) return FloatArray(0) }
        return StageLatency.measure(StageLatency.FACE_EMBEDDING) {
            try {
                val input = ImagePreprocess.cavaFace(alignedFace)
                val raw = NativeInference.cavaFaceEmbed(cavaHandle, input) ?: return@measure FloatArray(0)
                EmbeddingCodec.l2Normalize(raw)
            } catch (t: Throwable) {
                Log.w(TAG, "embedFace failed", t); FloatArray(0)
            }
        }
    }

    /**
     * Whisper transcript for the PCM16 window. CP3 only wires loading; the log-mel front-end and
     * decode loop land in **CP5** (§22), so this returns "" until then (callers use the
     * `SpeechRecognizer` fallback). [sampleRate] is retained for the CP5 mel front-end.
     */
    fun transcribe(pcmAudio: ShortArray, sampleRate: Int): String {
        if (whisperHandle == 0L) return ""
        Log.d(TAG, "transcribe(${pcmAudio.size} samples @ ${sampleRate}Hz) — mel front-end pending CP5")
        return ""
    }

    fun close() {
        if (cavaHandle != 0L) safeUnit { NativeInference.cavaFaceFree(cavaHandle) }; cavaHandle = 0L
        if (whisperHandle != 0L) safeUnit { NativeInference.whisperFree(whisperHandle) }; whisperHandle = 0L
    }

    private inline fun safe(block: () -> Long): Long = try { block() } catch (t: Throwable) {
        Log.w(TAG, "native load failed", t); 0L
    }

    private inline fun safeUnit(block: () -> Unit) = try { block() } catch (_: Throwable) {}

    private companion object {
        const val TAG = "QnnModelRunner"
    }
}
