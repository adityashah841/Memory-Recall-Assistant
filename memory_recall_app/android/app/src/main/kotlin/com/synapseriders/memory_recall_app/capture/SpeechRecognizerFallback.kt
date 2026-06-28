package com.synapseriders.memory_recall_app.capture

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Live-mic ASR fallback via Android [SpeechRecognizer] (IMPLEMENTATION_PLAN.md §6: "fallback if the
 * QNN binary fails to load — never crash").
 *
 * Note: unlike Whisper, `SpeechRecognizer` consumes the **microphone directly**, not a PCM buffer, so
 * this is a live-recognition fallback the capture loop (CP6) starts when `QnnModelRunner` reports
 * Whisper unavailable. Results are delivered to [onResult]; all callbacks fire on the main thread, so
 * construct/start/stop this on the main thread.
 */
class SpeechRecognizerFallback(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    /** True when on-device recognition is available on this device. */
    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** Begin listening; [onResult] receives the best transcript when a phrase completes. */
    fun start(onResult: (String) -> Unit) {
        if (!isAvailable()) {
            Log.i(TAG, "SpeechRecognizer not available on this device")
            return
        }
        val sr = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotEmpty()) onResult(text)
            }

            override fun onError(error: Int) { Log.d(TAG, "SpeechRecognizer error=$error") }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try {
            sr.startListening(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "startListening failed", t)
        }
    }

    fun stop() {
        recognizer?.run {
            try { stopListening() } catch (_: Throwable) {}
        }
    }

    fun destroy() {
        recognizer?.run {
            try { destroy() } catch (_: Throwable) {}
        }
        recognizer = null
    }

    private companion object {
        const val TAG = "SpeechFallback"
    }
}
