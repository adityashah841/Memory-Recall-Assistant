package com.synapseriders.memory_recall_app.ai

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Gemini Nano via the ML Kit GenAI **Prompt API** — the on-device LLM on Pixel phones (runs through the
 * Android AICore system service; no network at inference time). Stand-in for the Llama/Genie runner.
 *
 * The model is shared system-wide by AICore; if the device reports it as DOWNLOADABLE, [warmUp] starts
 * the one-time download in the background. Until it's AVAILABLE, [chat] returns "" and callers use
 * their template fallback — never crashes.
 */
class GeminiNanoLlm {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val model: GenerativeModel? by lazy {
        try {
            Generation.getClient()
        } catch (t: Throwable) {
            Log.w(TAG, "Prompt API client unavailable", t); null
        }
    }

    @Volatile private var available = false
    @Volatile private var downloadStarted = false

    /** Check availability and kick off the model download if needed. Non-blocking; never throws. */
    fun warmUp() {
        scope.launch { refreshStatus(startDownload = true) }
    }

    /**
     * Blocking chat completion (call off the main thread). Returns "" if Gemini Nano isn't ready,
     * errors, or exceeds [timeoutMs].
     */
    fun chat(system: String, user: String, maxTokens: Int, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String {
        val m = model ?: return ""
        return runBlocking {
            if (!available && !refreshStatus(startDownload = true)) return@runBlocking ""
            StageLatency.measure(StageLatency.LLM_GENERATION) {
                withTimeoutOrNull(timeoutMs) {
                    try {
                        val request = if (system.isBlank()) {
                            generateContentRequest(TextPart(user)) {
                                temperature = TEMPERATURE
                                topK = TOP_K
                                maxOutputTokens = maxTokens
                            }
                        } else {
                            generateContentRequest(SystemInstruction(system), TextPart(user)) {
                                temperature = TEMPERATURE
                                topK = TOP_K
                                maxOutputTokens = maxTokens
                            }
                        }
                        m.generateContent(request).candidates.firstOrNull()?.text?.trim().orEmpty()
                    } catch (t: Throwable) {
                        Log.w(TAG, "generateContent failed", t); ""
                    }
                } ?: "".also { Log.w(TAG, "generation timed out after ${timeoutMs}ms") }
            }
        }
    }

    fun close() {
        try { model?.close() } catch (_: Throwable) {}
    }

    /** Returns true when Gemini Nano is ready; optionally starts the one-time download. */
    private suspend fun refreshStatus(startDownload: Boolean): Boolean {
        val m = model ?: return false
        return try {
            when (val status = m.checkStatus()) {
                FeatureStatus.AVAILABLE -> {
                    if (!available) Log.i(TAG, "Gemini Nano available")
                    available = true
                    true
                }
                FeatureStatus.DOWNLOADABLE -> {
                    if (startDownload && !downloadStarted) {
                        downloadStarted = true
                        scope.launch { download(m) }
                    }
                    false
                }
                FeatureStatus.DOWNLOADING -> {
                    Log.i(TAG, "Gemini Nano still downloading")
                    false
                }
                else -> {
                    Log.w(TAG, "Gemini Nano unavailable on this device (status=$status)")
                    false
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "checkStatus failed", t); false
        }
    }

    private suspend fun download(m: GenerativeModel) {
        Log.i(TAG, "downloading Gemini Nano…")
        try {
            m.download().collect { status ->
                when (status) {
                    is DownloadStatus.DownloadCompleted -> {
                        available = true
                        Log.i(TAG, "Gemini Nano download complete")
                    }
                    is DownloadStatus.DownloadFailed -> {
                        downloadStarted = false
                        Log.w(TAG, "Gemini Nano download failed", status.e)
                    }
                    else -> Unit
                }
            }
        } catch (t: Throwable) {
            downloadStarted = false
            Log.w(TAG, "Gemini Nano download error", t)
        }
    }

    private companion object {
        const val TAG = "GeminiNanoLlm"
        const val DEFAULT_TIMEOUT_MS = 12_000L
        // Low temperature: recall answers should be faithful to the stored records, not creative.
        const val TEMPERATURE = 0.3f
        const val TOP_K = 10
    }
}
